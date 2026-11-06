#include "styled_document.h"
#include "document_layout.h"
#include "drawing_plan.h"
#ifdef DC_WINDOWS_TEXT_ENGINE
#include "windows_text_engine.hpp"
#endif
#include <rapidjson/document.h>
#include <algorithm>
#include <bit>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <vector>

using Clock = std::chrono::steady_clock;
static std::string read(const char* path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error(std::string("Cannot read ") + path);
    return {(std::istreambuf_iterator<char>(f)), {}};
}
static double ms(Clock::time_point start) {
    return std::chrono::duration<double,std::milli>(Clock::now()-start).count();
}
static double median(std::vector<double> v) { std::sort(v.begin(),v.end()); return v[v.size()/2]; }
static void check(bool valid, const char* message) { if (!valid) throw std::runtime_error(message); }
static uint64_t geometry_digest(dc_layout_view v) {
    uint64_t hash=14695981039346656037ull;
    auto integer=[&](uint32_t n){for(unsigned i=0;i<4;++i){hash^=(n>>(i*8))&255;hash*=1099511628211ull;}};
    auto number=[&](float n){integer(std::bit_cast<uint32_t>(n));};
    for(size_t i=0;i<v.paragraph_count;++i){const auto& p=v.paragraphs[i];integer(p.paragraph);integer(p.source);number(p.x);number(p.y);number(p.width);number(p.height);integer(p.lines);}
    for(size_t i=0;i<v.cell_count;++i){const auto& c=v.cells[i];integer(c.cell);number(c.x);number(c.y);number(c.width);number(c.height);}
    for(size_t i=0;i<v.source_count;++i){number(v.sources[i].width);number(v.sources[i].height);}
    return hash;
}
static void validate(dc_prepared_view v) {
    for (size_t i=0;i<v.run_count;++i) {
        const auto& r=v.runs[i];
        check(uint64_t(r.text_offset)+r.text_bytes<=v.text_bytes,"Text range invalid");
        check(r.style<v.style_count && r.paragraph<v.paragraph_count,"Run owner invalid");
        check(r.ruby==UINT32_MAX || r.ruby<v.ruby_count,"Ruby owner invalid");
    }
    for (size_t i=0;i<v.paragraph_count;++i) {
        const auto& p=v.paragraphs[i];
        check(uint64_t(p.first_run)+p.run_count<=v.run_count,"Paragraph range invalid");
        for (uint32_t r=0;r<p.run_count;++r) check(v.runs[p.first_run+r].paragraph==i,"Noncontiguous paragraph");
    }
    for (size_t i=0;i<v.ruby_count;++i) {
        const auto& r=v.rubies[i];
        check(uint64_t(r.first_base_run)+r.base_run_count<=v.run_count,"Ruby base invalid");
        check(uint64_t(r.first_reading_run)+r.reading_run_count<=v.run_count,"Ruby reading invalid");
    }
}
int main(int argc,char** argv) {
    try {
        check(argc>=3,"Usage: dictionary_styled_bench bundle.json base.css [iterations=31] [font.otf enables layout]");
        const int iterations=argc>3?std::stoi(argv[3]):31;
        check(iterations>=3 && iterations<=10000,"Invalid iteration count");
        const auto raw=read(argv[1]); const auto base=read(argv[2]);
        rapidjson::Document bundle; bundle.Parse(raw.data(),raw.size());
        check(!bundle.HasParseError() && bundle.HasMember("cases"),"Invalid bundle");
        const bool bodies=!bundle["cases"].Empty() && bundle["cases"][0].HasMember("glossary");
        if(bodies) {
            rapidjson::Document normalized;normalized.SetObject();auto& a=normalized.GetAllocator();
            rapidjson::Value entries(rapidjson::kArrayType),entry(rapidjson::kObjectType),styles(rapidjson::kArrayType),results(rapidjson::kArrayType);
            std::map<std::string,std::string> names;
            for(const auto& body:bundle["cases"].GetArray()) {
                const std::string css=body["css"].GetString();
                auto [it,inserted]=names.try_emplace(css,"sheet-"+std::to_string(names.size()));
                if(inserted) {rapidjson::Value s(rapidjson::kObjectType);s.AddMember("dictName",rapidjson::Value(it->second.c_str(),a),a);s.AddMember("styles",rapidjson::Value(css.c_str(),a),a);styles.PushBack(s,a);}
                rapidjson::Value gloss(rapidjson::kObjectType),glosses(rapidjson::kArrayType),term(rapidjson::kObjectType),result(rapidjson::kObjectType);
                gloss.AddMember("dictName",rapidjson::Value(it->second.c_str(),a),a);gloss.AddMember("glossary",rapidjson::Value(body["glossary"],a),a);
                glosses.PushBack(gloss,a);term.AddMember("glossaries",glosses,a);result.AddMember("term",term,a);results.PushBack(result,a);
                check(body["width"].GetInt()==256 && body["fontSize"].GetInt()==16,"Body fixture needs explicit width/font configuration support");
            }
            rapidjson::Value options(rapidjson::kObjectType);options.AddMember("customCss","",a);
            entry.AddMember("id","raw-bodies-lookup-2",a);entry.AddMember("styles",styles,a);entry.AddMember("results",results,a);entry.AddMember("options",options,a);
            entries.PushBack(entry,a);normalized.AddMember("cases",entries,a);bundle.Swap(normalized);
        }
        const bool with_layout=argc>4;
        const bool with_commands=argc>5 && std::string_view(argv[5])=="commands";
#ifdef DC_WINDOWS_TEXT_ENGINE
        std::unique_ptr<WindowsTextEngine> windows;
        dc_text_engine engine{};
        if(with_layout){windows=std::make_unique<WindowsTextEngine>(argv[4]);engine=windows->callbacks();}
#else
        check(!with_layout,"No desktop text engine on this platform");
        dc_text_engine engine{};
#endif
        std::cout<<"{\"scope\":\"PC native parse/cascade/typed inputs; optional DirectWrite shaping and portable placement; not Android popup latency\",\"layout_enabled\":"<<(with_layout?"true":"false")<<",\"raw_bodies_no_dedup\":"<<(bodies?"true":"false")<<",\"iterations\":"<<iterations<<",\"warmups\":3,\"cases\":[";
        bool first=true;
        for (const auto& entry:bundle["cases"].GetArray()) {
            std::map<std::string,uint32_t> dictionaries;
            std::vector<std::string> css{base};
            for(const auto& style:entry["styles"].GetArray()) {
                dictionaries[style["dictName"].GetString()]=static_cast<uint32_t>(css.size());
                css.push_back(base+"\n"+style["styles"].GetString());
            }
            const std::string config="\n:root{--font-size-no-units:16}html,body{font-size:16px}rt{font-size:.5em}";
            for(auto& s:css) s+=config+std::string(entry["options"]["customCss"].GetString());
            std::vector<dc_css_source> sheets;
            for(const auto& s:css) sheets.push_back({s.data(),s.size()});
            dc_error error{}; dc_stylesheets* context=nullptr;
            auto start=Clock::now();
            check(!dc_stylesheets_create(sheets.data(),sheets.size(),&context,&error),error.message);
            const auto css_ms=ms(start);
            std::vector<std::string> texts; std::vector<uint32_t> indices;
            std::set<std::pair<std::string,std::string>> seen;
            for(const auto& result:entry["results"].GetArray()) for(const auto& gloss:result["term"]["glossaries"].GetArray()) {
                std::string dict=gloss["dictName"].GetString(),text=gloss["glossary"].GetString();
                if(!bodies && !seen.emplace(dict,text).second) continue;
                indices.push_back(dictionaries.contains(dict)?dictionaries.at(dict):0);
                texts.push_back(std::move(text));
            }
            std::vector<dc_source> sources; for(const auto& t:texts) sources.push_back({t.data(),t.size()});
            std::vector<double> parse,styled,total,layout_samples;
            std::vector<double> drawing_input_samples,drawing_measure_samples,drawing_finish_samples;
            uint64_t expected=0,expected_geometry=0; dc_prepared_view view{};
            size_t runs=0,paragraphs=0,rubies=0,tables=0,styles=0; uint64_t unresolved=0,unsupported=0,input_rubies=0;
            uint64_t prepares=0,wraps=0,layout_unresolved=0;size_t placed=0,placed_cells=0;
            size_t commands_count=0,hits_count=0,requests_count=0,shaping_count=0;uint64_t drawing_lines=0;
            std::unique_ptr<dc_stylesheets,decltype(&dc_stylesheets_destroy)> owner(context,dc_stylesheets_destroy);
            for(int pass=-3;pass<iterations;++pass) {
                dc_stylesheets_verify_candidates(context,pass==-3);
#ifdef DC_WINDOWS_TEXT_ENGINE
                if(with_layout)windows->force_intrinsic_for_verification(pass==-3);
#endif
                start=Clock::now(); dc_document* doc=nullptr;
                check(!dc_prepare_utf8(sources.data(),sources.size(),&doc,&error),error.message);
                std::unique_ptr<dc_document,decltype(&dc_document_destroy)> parsed(doc,dc_document_destroy);
                input_rubies=dc_document_counts(doc).ruby_groups;
                const auto parse_ms=ms(start); const auto lower_start=Clock::now();
                dc_prepared_document* prepared=nullptr;
                check(!dc_compile_styled(doc,context,indices.data(),indices.size(),&prepared,&error),error.message);
                const auto lower_ms=ms(lower_start);
                std::unique_ptr<dc_prepared_document,decltype(&dc_prepared_document_destroy)> output(prepared,dc_prepared_document_destroy);
                std::unique_ptr<dc_layout_document,decltype(&dc_layout_document_destroy)> layout(nullptr,dc_layout_document_destroy);
                const auto layout_start=Clock::now();
                double drawing_input_ms=0,drawing_measure_ms=0,drawing_finish_ms=0;
                if(with_commands) {
#ifdef DC_WINDOWS_TEXT_ENGINE
                    std::vector<float> widths(sources.size(),256.f);dc_drawing_plan* plan=nullptr;
                    check(!dc_drawing_begin(prepared,widths.data(),widths.size(),0xff000000,0xff808080,&plan,&error),error.message);
                    std::unique_ptr<dc_drawing_plan,decltype(&dc_drawing_plan_destroy)> drawing(plan,dc_drawing_plan_destroy);
                    drawing_input_ms=ms(layout_start);const auto measure_start=Clock::now();
                    windows->measure_drawing_plan(plan);
                    drawing_measure_ms=ms(measure_start);const auto finish_start=Clock::now();
                    check(!dc_drawing_finish(plan,&error),error.message);
                    drawing_finish_ms=ms(finish_start);
                    const auto drawn=dc_drawing_plan_view(plan);
                    check(drawn.source_count==sources.size() && drawn.lines>=dc_prepared_document_view(prepared).paragraph_count,"Incomplete drawing sources/paragraphs");
                    uint64_t digest=1469598103934665603ull;
                    for(size_t i=0;i<drawn.command_count;++i) {
                        const auto& command=drawn.commands[i];
                        check(command.source<drawn.source_count && std::isfinite(command.x) && std::isfinite(command.y) && command.width>=0 && command.height>=0,"Invalid drawing command");
                        const auto* bytes=reinterpret_cast<const unsigned char*>(&command);
                        for(size_t b=0;b<sizeof(command);++b){digest^=bytes[b];digest*=1099511628211ull;}
                    }
                    check(!expected_geometry || expected_geometry==digest,"Nondeterministic native drawing commands");expected_geometry=digest;
                    prepares=dc_prepared_document_view(prepared).paragraph_count;wraps=prepares;placed=prepares;placed_cells=dc_prepared_document_view(prepared).cell_count;
                    commands_count=drawn.command_count;hits_count=drawn.hit_count;requests_count=drawn.request_count;shaping_count=drawn.shaping_run_count;drawing_lines=drawn.lines;
#endif
                } else if(with_layout) {
                    std::vector<float> widths(sources.size(),256.f);dc_layout_document* positioned=nullptr;
                    check(!dc_layout(prepared,&engine,widths.data(),widths.size(),&positioned,&error),error.message);layout.reset(positioned);
                }
                const auto layout_ms=ms(layout_start),total_ms=ms(start);
                if(layout) {
                    const auto positions=dc_layout_document_view(layout.get());
                    prepares=positions.text_prepares;wraps=positions.text_wraps;layout_unresolved=positions.unresolved_lengths;
                    placed=positions.paragraph_count;placed_cells=positions.cell_count;
                    check(placed==dc_prepared_document_view(prepared).paragraph_count,"Not all prepared paragraphs were placed");
                    std::vector<bool> seen_paragraphs(placed);
                    for(size_t i=0;i<placed;++i) {
                        check(std::isfinite(positions.paragraphs[i].y) && positions.paragraphs[i].height>=0,"Invalid paragraph placement");
                        const auto p=positions.paragraphs[i].paragraph;
                        check(p<placed && !seen_paragraphs[p],"Duplicate or missing paragraph placement");seen_paragraphs[p]=true;
                    }
                    const auto digest=geometry_digest(positions);
                    check(!expected_geometry || expected_geometry==digest,"Geometry differs from eager-intrinsic control or between samples");expected_geometry=digest;
                }
                view=dc_prepared_document_view(prepared); validate(view);
                auto digest=dc_prepared_document_digest(prepared);
                check(!expected || expected==digest,"Nondeterministic styled output"); expected=digest;
                runs=view.run_count; paragraphs=view.paragraph_count; rubies=view.ruby_count; tables=view.table_count; styles=view.style_count;
                unresolved=view.unresolved_values; unsupported=view.unsupported_nodes;
                if(pass>=0){parse.push_back(parse_ms);styled.push_back(lower_ms);layout_samples.push_back(layout_ms);total.push_back(total_ms);drawing_input_samples.push_back(drawing_input_ms);drawing_measure_samples.push_back(drawing_measure_ms);drawing_finish_samples.push_back(drawing_finish_ms);}
            }
            if(!first)std::cout<<','; first=false;
            std::cout<<"{\"id\":\""<<entry["id"].GetString()<<"\",\"sources\":"<<sources.size()
                <<",\"css_parse_ms\":"<<css_ms<<",\"parse_ms\":"<<median(parse)<<",\"style_lower_ms\":"<<median(styled)
                <<",\"total_ms\":"<<median(total)<<",\"runs\":"<<runs<<",\"paragraphs\":"<<paragraphs
                <<",\"text_layout_ms\":"<<median(layout_samples)<<",\"text_prepares\":"<<prepares<<",\"text_wraps\":"<<wraps<<",\"placed_paragraphs\":"<<placed<<",\"placed_cells\":"<<placed_cells<<",\"layout_unresolved_lengths\":"<<layout_unresolved
                <<",\"rubies\":"<<rubies<<",\"tables\":"<<tables<<",\"styles\":"<<styles
                <<",\"input_rubies\":"<<input_rubies
                <<",\"drawing_enabled\":"<<(with_commands?"true":"false")<<",\"drawing_commands\":"<<commands_count<<",\"hit_regions\":"<<hits_count<<",\"measurement_requests\":"<<requests_count<<",\"drawing_lines\":"<<drawing_lines
                <<",\"shaping_runs\":"<<shaping_count<<",\"drawing_input_ms\":"<<median(drawing_input_samples)<<",\"platform_measure_ms\":"<<median(drawing_measure_samples)<<",\"drawing_finish_ms\":"<<median(drawing_finish_samples)
                <<",\"unresolved_values\":"<<unresolved<<",\"unsupported_features\":"<<unsupported
                <<",\"digest\":\""<<expected<<"\",\"geometry_digest\":\""<<expected_geometry<<"\",\"validated\":true,\"total_samples_ms\":[";
            for(size_t i=0;i<total.size();++i){if(i)std::cout<<',';std::cout<<total[i];}
            std::cout<<"]}";
        }
        std::cout<<"]}\n";
    } catch(const std::exception& e) {std::cerr<<e.what()<<'\n';return 1;}
}
