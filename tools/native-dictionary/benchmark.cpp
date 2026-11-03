#include "dictionary_core.h"
#include "document_storage.hpp"
#include <rapidjson/document.h>
#include <rapidjson/error/en.h>
#ifdef DC_WITH_HOSHI_ADAPTER
#include "hoshidicts_glossaries.hpp"
#endif
#ifdef DC_WITH_GLAZE
#include <glaze/json.hpp>
#endif
#include <algorithm>
#include <chrono>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <set>
#include <sstream>

using namespace dictionary_core;
using Clock = std::chrono::steady_clock;
struct Case {
    std::string name;
    std::vector<std::string> sources;
    std::vector<dc_source> input;
#ifdef DC_WITH_HOSHI_ADAPTER
    std::vector<GlossaryEntry> native_glossaries;
#endif
    size_t bytes = 0;
};

static void copy_dom(const rapidjson::Value& value, Storage& out, size_t depth = 0) {
    if (depth > 512) throw std::runtime_error("DOM depth limit");
    if (value.IsObject()) {
        const auto root = out.begin(Kind::object);
        for (auto it = value.MemberBegin(); it != value.MemberEnd(); ++it) {
            const std::string_view key(it->name.GetString(), it->name.GetStringLength());
            out.scalar(Kind::key, key, field_name(key)); copy_dom(it->value, out, depth + 1);
        }
        out.end(root);
    } else if (value.IsArray()) {
        const auto root = out.begin(Kind::array);
        for (const auto& child : value.GetArray()) copy_dom(child, out, depth + 1);
        out.end(root);
    } else if (value.IsString()) out.scalar(Kind::string, {value.GetString(), value.GetStringLength()});
    else if (value.IsNull()) out.scalar(Kind::null_value, {});
    else if (value.IsBool()) out.scalar(Kind::boolean, value.GetBool() ? "true" : "false");
    else if (value.IsInt64()) out.number(value.GetInt64());
    else if (value.IsUint64()) out.number(value.GetUint64());
    else out.number(value.GetDouble());
}
#ifdef DC_WITH_GLAZE
static void copy_glaze(const glz::generic& value, Storage& out, size_t depth = 0) {
    if (depth > 512) throw std::runtime_error("Glaze depth limit");
    if (value.is_object()) {
        const auto root = out.begin(Kind::object);
        for (const auto& [key, child] : value.get_object()) {
            out.scalar(Kind::key, key, field_name(key)); copy_glaze(child, out, depth + 1);
        }
        out.end(root);
    } else if (value.is_array()) {
        const auto root = out.begin(Kind::array);
        for (const auto& child : value.get_array()) copy_glaze(child, out, depth + 1);
        out.end(root);
    } else if (value.is_string()) out.scalar(Kind::string, value.get_string());
    else if (value.is_null()) out.scalar(Kind::null_value, {});
    else if (value.is_boolean()) out.scalar(Kind::boolean, value.get_boolean() ? "true" : "false");
    else out.number(value.get_number());
}
#endif

static std::unique_ptr<dc_document> prepare(const Case& item, int mode) {
    if (mode == 0) {
        dc_document* result = nullptr; dc_error error{};
#ifdef DC_WITH_HOSHI_ADAPTER
        const auto status = prepare_hoshi_glossaries(item.native_glossaries, &result, &error);
#else
        const auto status = dc_prepare_utf8(item.input.data(), item.input.size(), &result, &error);
#endif
        if (status)
            throw std::runtime_error("Source " + std::to_string(error.source) + ": " + error.message);
        return std::unique_ptr<dc_document>(result);
    }
    auto result = std::make_unique<dc_document>();
    auto& out = result->storage;
    out.strings.reserve(item.bytes / 2); out.tokens.reserve(item.bytes / 8); out.operations.reserve(item.bytes / 24);
    for (const auto& text : item.sources) {
        const auto first = text.find_first_not_of(" \n\r\t");
        const bool json = first != std::string::npos && (text[first] == '[' || text[first] == '{');
        const auto root = checked_index(out.tokens.size());
        if (json) {
            if (mode == 1) {
                rapidjson::Document dom;
                dom.Parse<rapidjson::kParseValidateEncodingFlag>(text.data(), text.size());
                if (dom.HasParseError()) throw std::runtime_error(rapidjson::GetParseError_En(dom.GetParseError()));
                copy_dom(dom, out);
            }
#ifdef DC_WITH_GLAZE
            else {
                glz::generic dom;
                if (auto error = glz::read_json(dom, text)) throw std::runtime_error(glz::format_error(error, text));
                copy_glaze(dom, out);
            }
#endif
        } else {
            out.scalar(Kind::string, text);
            if (text.find('<') != std::string::npos) ++out.counts.html_sources; else ++out.counts.plain_sources;
        }
        out.finish_source(root, json);
    }
    out.finish(); return result;
}
static double median(std::vector<double> values) {
    std::sort(values.begin(), values.end()); return values[values.size() / 2];
}
int main(int argc, char** argv) {
    try {
        if (argc < 2) throw std::runtime_error("Usage: dictionary_core_bench bundle.json [iterations=31]");
        const int iterations = argc > 2 ? std::stoi(argv[2]) : 31;
        if (iterations < 3 || iterations > 10000) throw std::runtime_error("iterations must be 3..10000");
        std::ifstream input(argv[1], std::ios::binary); if (!input) throw std::runtime_error("Cannot open corpus");
        const std::string raw((std::istreambuf_iterator<char>(input)), {});
        rapidjson::Document bundle; bundle.Parse(raw.data(), raw.size());
        if (bundle.HasParseError() || !bundle.HasMember("cases")) throw std::runtime_error("Invalid replay bundle");
        std::vector<Case> corpus;
        for (const auto& entry : bundle["cases"].GetArray()) {
            Case item; item.name = entry["id"].GetString();
            std::set<std::pair<std::string,std::string>> seen;
            for (const auto& result : entry["results"].GetArray())
                for (const auto& gloss : result["term"]["glossaries"].GetArray()) {
                    std::string dictionary = gloss["dictName"].GetString();
                    std::string source(gloss["glossary"].GetString(), gloss["glossary"].GetStringLength());
                    if (seen.emplace(dictionary, source).second) {
                        item.bytes += source.size();
#ifdef DC_WITH_HOSHI_ADAPTER
                        GlossaryEntry native;
                        native.dict_name = dictionary;
                        native.glossary = source;
                        item.native_glossaries.push_back(std::move(native));
#endif
                        item.sources.push_back(std::move(source));
                    }
                }
            corpus.push_back(std::move(item));
        }
        for (auto& item : corpus) for (const auto& text : item.sources) item.input.push_back({text.data(),text.size()});
        const std::vector<std::string> names = {"rapidjson_sax_arena", "rapidjson_dom_arena"
#ifdef DC_WITH_GLAZE
            , "glaze_dom_arena"
#endif
        };
        std::cout << "{\n\"scope\":\"desktop native UTF-8 parse + owned tape + structural lowering; not popup latency\",\n"
            << "\"compiler\":\"" << __VERSION__ << "\",\n"
#ifdef DC_WITH_HOSHI_ADAPTER
            << "\"native_hoshi_adapter\":true,\n"
#endif
            << "\"excluded\":[\"bundle I/O\",\"CSS resolution\",\"shaping\",\"line breaking\",\"geometry\",\"painting\",\"JNI/transcoding\",\"final document destruction\"],\n"
            << "\"deduplication\":\"dictionary+exact glossary within each case, not cross-term cache\",\n"
            << "\"iterations\":" << iterations << ",\"warmups\":5,\n\"cases\":[\n";
        bool first = true;
        for (const auto& item : corpus) {
            uint64_t expected = 0; dc_counts counts{};
            std::vector<std::vector<double>> samples(names.size());
            for (int pass = -5; pass < iterations; ++pass) {
                for (size_t offset = 0; offset < names.size(); ++offset) {
                    const auto mode = (offset + static_cast<size_t>(pass + 5)) % names.size();
                    const auto start = Clock::now();
                    auto output = prepare(item, static_cast<int>(mode));
                    const auto elapsed = std::chrono::duration<double,std::milli>(Clock::now()-start).count();
                    const auto digest = dc_document_digest(output.get());
                    if (expected && digest != expected) throw std::runtime_error(item.name + ": output mismatch in " + names[mode]);
                    expected = digest; counts = dc_document_counts(output.get());
                    if (pass >= 0) samples[mode].push_back(elapsed);
                }
            }
            if (!first) std::cout << ",\n"; first = false;
            std::cout << "{\"id\":\"" << item.name << "\",\"sources\":" << counts.sources << ",\"input_bytes\":" << item.bytes
                << ",\"json_tokens\":" << counts.json_tokens << ",\"operations\":" << counts.operations
                << ",\"text_bytes\":" << counts.text_bytes << ",\"elements\":" << counts.elements
                << ",\"ruby_groups\":" << counts.ruby_groups << ",\"ruby_readings\":" << counts.ruby_readings
                << ",\"tables\":" << counts.tables << ",\"rows\":" << counts.rows << ",\"cells\":" << counts.cells
                << ",\"plain_sources\":" << counts.plain_sources << ",\"html_sources\":" << counts.html_sources
                << ",\"digest\":\"" << expected << "\",\"verified_equal\":true,\"timings\":{";
            for (size_t mode = 0; mode < names.size(); ++mode) {
                if (mode) std::cout << ',';
                auto sorted = samples[mode]; std::sort(sorted.begin(),sorted.end());
                std::cout << '\"' << names[mode] << "\":{\"median_ms\":" << std::fixed << std::setprecision(4) << median(sorted)
                    << ",\"p90_ms\":" << sorted[(sorted.size()-1)*9/10] << ",\"samples_ms\":[";
                for (size_t s = 0; s < samples[mode].size(); ++s) { if (s) std::cout << ','; std::cout << samples[mode][s]; }
                std::cout << "]}";
            }
            std::cout << "}}";
        }
        std::cout << "\n]}\n";
    } catch (const std::exception& failure) { std::cerr << failure.what() << '\n'; return 1; }
}
