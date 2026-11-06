#include "drawing_plan.h"
#include "css_numeric.hpp"
#include "resolved_style.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <memory>
#include <map>
#include <stdexcept>
#include <string>
#include <string_view>
#include <tuple>
#include <vector>

namespace {
constexpr uint32_t none = UINT32_MAX;
static_assert(sizeof(dc_drawing_font) == 68 && sizeof(dc_draw_command) == 64);
static_assert(sizeof(dc_measure_request) == 20 && sizeof(dc_drawing_hit) == 28);
uint32_t index(size_t n) {
    if (n > INT32_MAX)
        throw std::length_error("Drawing buffer limit");
    return static_cast<uint32_t>(n);
}
float number(std::string_view value, float fallback = 0) {
    if (value.empty())
        return fallback;
    std::string s(value);
    char *end = nullptr;
    float n = std::strtof(s.c_str(), &end);
    return end != s.c_str() && std::isfinite(n) ? n : fallback;
}
uint32_t color(std::string_view value, uint32_t fallback,unsigned depth=0) {
    if(depth>32)return fallback;
    if (value.empty() || value == "currentcolor" || value == "currentColor")
        return fallback;
    if (value == "transparent")
        return 0;
    if (value[0] == '#') {
        auto hex = [](char c) -> int {
            if (c >= '0' && c <= '9')
                return c - '0';
            if (c >= 'a' && c <= 'f')
                return c - 'a' + 10;
            if (c >= 'A' && c <= 'F')
                return c - 'A' + 10;
            return -1;
        };
        uint32_t n = 0;
        for (char c : value.substr(1)) {
            int d = hex(c);
            if (d < 0)
                return fallback;
            n = (n << 4) | d;
        }
        if (value.size() == 4 || value.size() == 5) {
            uint32_t r = (n >> (value.size() == 5 ? 12 : 8)) & 15, g = (n >> (value.size() == 5 ? 8 : 4)) & 15,
                     b = (n >> (value.size() == 5 ? 4 : 0)) & 15, a = value.size() == 5 ? n & 15 : 15;
            return (a * 17 << 24) | (r * 17 << 16) | (g * 17 << 8) | b * 17;
        }
        if (value.size() == 7)
            return 0xff000000 | n;
        if (value.size() == 9)
            return (n >> 8) | (n << 24);
    }
    struct Name {
        std::string_view name;
        uint32_t value;
    };
    static constexpr Name names[] = {
        {"black", 0xff000000},  {"white", 0xffffffff},   {"red", 0xffff0000},    {"green", 0xff008000},
        {"blue", 0xff0000ff},   {"gray", 0xff808080},    {"grey", 0xff808080},   {"silver", 0xffc0c0c0},
        {"yellow", 0xffffff00}, {"orange", 0xffffa500},  {"purple", 0xff800080}, {"pink", 0xffffc0cb},
        {"navy", 0xff000080},   {"teal", 0xff008080},    {"lime", 0xff00ff00},   {"maroon", 0xff800000},
        {"aqua", 0xff00ffff},   {"fuchsia", 0xffff00ff}, {"olive", 0xff808000},
        {"brown",0xffa52a2a}, {"crimson",0xffdc143c}, {"goldenrod",0xffdaa520}};
    for (auto n : names)
        if (value == n.name)
            return n.value;
    if(value.starts_with("color-mix(") && value.ends_with(')')) {
        auto trim=[](std::string_view s) {
            const auto first=s.find_first_not_of(" \r\n\t");
            return first==s.npos?std::string_view{}:s.substr(first,s.find_last_not_of(" \r\n\t")-first+1);
        };
        std::array<std::string_view,3> parts{};size_t begin=10,count=0;unsigned nesting=0;
        for(size_t i=begin;i<value.size()-1;++i) {
            if(value[i]=='(')++nesting;
            if(value[i]==')' && nesting)--nesting;
            if(value[i]==',' && nesting==0) {
                if(count>=2)return fallback;
                parts[count++]=trim(value.substr(begin,i-begin));begin=i+1;
            }
        }
        if(count!=2 || parts[0]!="in srgb")return fallback;
        parts[2]=trim(value.substr(begin,value.size()-1-begin));
        float weights[]{-1,-1};uint32_t colors[2]{};
        for(int i=0;i<2;++i) {
            auto part=parts[i+1];
            if(part.ends_with('%')) {
                auto split=part.find_last_of(" \t\n");
                if(split==part.npos)return fallback;
                weights[i]=number(part.substr(split+1),-100)/100;
                if(weights[i]<0)return fallback;
                part=trim(part.substr(0,split));
            }
            colors[i]=color(part,fallback,depth+1);
        }
        if(weights[0]<0 && weights[1]<0)weights[0]=weights[1]=.5f;
        else if(weights[0]<0)weights[0]=std::max(0.f,1-weights[1]);
        else if(weights[1]<0)weights[1]=std::max(0.f,1-weights[0]);
        const float sum=weights[0]+weights[1];if(sum<=0)return 0;
        const float a=(colors[0]>>24)/255.f*weights[0]/sum,b=(colors[1]>>24)/255.f*weights[1]/sum;
        if(a+b<=0)return 0;
        uint32_t result=static_cast<uint32_t>(std::lround((a+b)*std::min(1.f,sum)*255))<<24;
        for(int shift:{16,8,0})result|=static_cast<uint32_t>(std::lround((((colors[0]>>shift)&255)*a+((colors[1]>>shift)&255)*b)/(a+b)))<<shift;
        return result;
    }
    if (value.starts_with("rgb(") || value.starts_with("rgba(")) {
        std::string s(value.substr(value.find('(') + 1));
        float channels[4]{0, 0, 0, 1};
        char *p = s.data();
        for (int i = 0; i < 4; ++i) {
            while (*p == ' ' || *p == ',' || *p == '/')
                ++p;
            if (*p == ')' || !*p)
                break;
            char *end;
            float n = std::strtof(p, &end);
            if (end == p)
                return fallback;
            p = end;
            bool percent = *p == '%';
            if (percent)
                ++p;
            channels[i] = i < 3 ? (percent ? n * 2.55f : n) : (percent ? n / 100.f : n);
        }
        auto byte = [](float n) { return static_cast<uint32_t>(std::clamp(std::lround(n), 0l, 255l)); };
        return (byte(channels[3] * 255) << 24) | (byte(channels[0]) << 16) | (byte(channels[1]) << 8) |
               byte(channels[2]);
    }
    return fallback;
}
void append_utf16(std::vector<uint16_t> &out, std::string_view input) {
    for (size_t i = 0; i < input.size();) {
        uint32_t c = static_cast<uint8_t>(input[i++]);
        unsigned more = 0;
        if (c >= 0xf0) {
            c &= 7;
            more = 3;
        } else if (c >= 0xe0) {
            c &= 15;
            more = 2;
        } else if (c >= 0xc0) {
            c &= 31;
            more = 1;
        }
        while (more--) {
            if (i >= input.size() || (static_cast<uint8_t>(input[i]) & 0xc0) != 0x80)
                throw std::runtime_error("Invalid drawing UTF-8");
            c = (c << 6) | (input[i++] & 63);
        }
        if (c > 0x10ffff)
            throw std::runtime_error("Invalid drawing codepoint");
        if (c > 0xffff) {
            c -= 0x10000;
            out.push_back(0xd800 + (c >> 10));
            out.push_back(0xdc00 + (c & 1023));
        } else
            out.push_back(c);
    }
}
struct Piece {
    uint32_t request, start, count;
    float width;
    bool trimmed = false;
};
struct Atom {
    uint32_t first = 0, count = 0, reading_first = 0, reading_count = 0, image = none;
    float width = 0, above = 0, below = 0, base_above = 0, unshifted_above = 0, left = 0, right = 0, reading_height = 0, reading_shift = 0;
    float overhang_start=0,overhang_end=0;
    uint8_t level = 0;
    bool legal = false, forced = false, space = false;
};
struct Paragraph {
    std::vector<Piece> pieces;
    std::vector<Atom> atoms;
    std::vector<dc_draw_command> commands;
    std::vector<dc_drawing_hit> hits;
    float min_width = 0, max_width = 0;
    uint32_t body_start = 0, body_count = 0, marker = none;
};
} // namespace
struct dc_drawing_plan {
    const dc_prepared_document *input;
    dc_prepared_view v;
    const dictionary_core::ResolvedStyles &resolved;
    std::vector<float> widths;
    std::vector<dc_drawing_font> fonts;
    struct TextSpacing { bool space_all = false, custom_features = false; };
    std::vector<TextSpacing> text_spacing;
    struct Border {std::array<float,4> widths{};std::array<uint32_t,4> colors{};};
    std::vector<Border> borders;
    struct Gradient {uint32_t inner=0,outer=0;float from=0,to=1;bool radial=false,circle=false;};
    std::vector<Gradient> gradients;
    std::string names;
    std::vector<uint16_t> text;
    std::vector<dc_measure_request> requests;
    std::vector<dc_measure_context> contexts;
    std::vector<dc_shaping_run> shaping_runs;
    std::vector<dc_run_metrics> metrics;
    std::vector<float> advances;
    std::vector<uint8_t> breaks, levels;
    std::vector<uint32_t> run_requests, image_by_run;
    std::vector<uint32_t> request_runs;
    std::vector<uint32_t> atomic_run_end;
    std::vector<float> run_left, run_right, request_shift;
    std::vector<std::vector<uint32_t>> paragraph_boxes;
    std::vector<Paragraph> paragraphs;
    std::vector<dc_draw_command> commands;
    std::vector<dc_drawing_hit> hits;
    std::vector<dc_drawing_source> sources;
    uint64_t lines = 0, rubies = 0;
    bool finished = false;
    std::string_view property(const dc_prepared_view &, uint32_t style, std::string_view name) const {
        return resolved.property(style, name);
    }

    float length(std::string_view s, uint32_t style, float basis, float fallback = 0) const {
        if (s.empty() || s == "auto" || s == "normal")
            return fallback;
        return resolved.length(style, s, basis).value_or(fallback);
    }
    explicit dc_drawing_plan(const dc_prepared_document *d, const float *w, size_t n, uint32_t fg, uint32_t border)
        : input(d), v(dc_prepared_document_view(d)), resolved(dictionary_core::resolved_styles(d)), widths(w, w + n), run_requests(v.run_count, none),
          image_by_run(v.run_count, none), paragraphs(v.paragraph_count) {
        if (n != v.source_count)
            throw std::invalid_argument("Drawing source widths mismatch");
        fonts.resize(v.style_count);
        text_spacing.resize(v.style_count);
        borders.resize(v.style_count);
        gradients.resize(v.style_count);
        for (uint32_t i = 0; i < v.style_count; ++i) {
            auto &f = fonts[i];
            auto prop = [&](std::string_view name) { return property(v, i, name); };
            const auto features = prop("font-feature-settings");
            text_spacing[i] = {prop("text-spacing-trim") == "space-all", !features.empty() && features != "normal"};
            f.size = v.styles[i].font_size_px;
            f.color = color(prop("color"), fg);
            f.background = color(prop("background-color"), color(prop("background"), 0));
            auto background=prop("background-image");
            if(background.empty() || background=="none")background=prop("background");
            if(background.starts_with("radial-gradient(") && background.ends_with(')')) {
                const auto content=background.substr(16,background.size()-17);
                size_t comma=content.npos;unsigned depth=0;
                for(size_t at=0;at<content.size();++at) {
                    if(content[at]=='(')++depth;else if(content[at]==')' && depth)--depth;
                    else if(content[at]==',' && !depth){if(comma!=content.npos){comma=content.npos;break;}comma=at;}
                }
                if(comma!=content.npos) {
                    auto stop=[&](std::string_view value,uint32_t& paint,float& position) {
                        const auto end=value.find_last_not_of(' ');if(end==value.npos)return false;value=value.substr(0,end+1);
                        const auto split=value.find_last_of(' ');if(split==value.npos || !value.ends_with('%'))return false;
                        auto first=value.find_first_not_of(' ');paint=color(value.substr(first,split-first),0);
                        position=number(value.substr(split+1),0)/100;return true;
                    };
                    auto& gradient=gradients[i];
                    gradient.radial=stop(content.substr(0,comma),gradient.inner,gradient.from) &&
                        stop(content.substr(comma+1),gradient.outer,gradient.to) && gradient.to>gradient.from;
                    gradient.circle=prop("clip-path").starts_with("circle(");
                    if(gradient.radial)f.background=gradient.inner;
                }
            }
            f.border = color(prop("border-color"), border);
            auto weight = prop("font-weight");
            f.weight = weight == "bold" || weight == "bolder"
                           ? 700
                           : static_cast<uint32_t>(std::clamp(number(weight, 400), 1.f, 1000.f));
            const auto align = prop("text-align"), white = prop("white-space"),
                       decoration = prop("text-decoration-line").empty() ? prop("text-decoration")
                                                                         : prop("text-decoration-line");
            f.flags = ((prop("font-style") == "italic" || prop("font-style") == "oblique") ? 1 : 0) |
                      (decoration.find("underline") != decoration.npos ? 2 : 0) |
                      (decoration.find("line-through") != decoration.npos ? 4 : 0) |
                      (prop("direction") == "rtl" ? 8 : 0) | ((white == "nowrap" || white == "pre") ? 16 : 0) |
                      (align == "center"                      ? 32
                       : (align == "right" || align == "end") ? 64
                       : align == "justify"                   ? 128
                                                              : 0) |
                      ((white == "pre" || white == "pre-wrap" || white == "break-spaces") ? 256 : 0) |
                      ((prop("display") == "inline" || prop("display") == "inline-block" || prop("display")=="inline-flex" || prop("display")=="flex") ? 512 : 0);
            auto line = prop("line-height");
            f.line_height = length(line, i, 0);
            if (!line.empty() && line.find_first_not_of("0123456789.+-") == line.npos)
                f.line_height = number(line) * f.size;
            f.letter_spacing = length(prop("letter-spacing"), i, 0);
            auto va = prop("vertical-align");
            f.baseline_shift = va == "super" ? f.size * .4f : va == "sub" ? -f.size * .2f : length(va, i, 0);
            f.stroke = prop("border-style") == "none" ? 0 : length(prop("border-width"), i, 0);
            auto border_value = prop("border");
            if (!border_value.empty()) {
                std::string value(border_value);
                size_t at = 0;
                while (at < value.size()) {
                    while (at < value.size() && value[at] == ' ')
                        ++at;
                    size_t begin = at;
                    unsigned depth = 0;
                    while (at < value.size()) {
                        if (value[at] == '(')
                            ++depth;
                        if (value[at] == ')' && depth)
                            --depth;
                        if (value[at] == ' ' && !depth)
                            break;
                        ++at;
                    }
                    auto token = std::string_view(value).substr(begin, at - begin);
                    if (token.empty())
                        break;
                    if (token == "none" || token == "hidden") {
                        f.stroke = 0;
                        break;
                    }
                    if (token == "thin")
                        f.stroke = 1;
                    else if (token == "medium")
                        f.stroke = 3;
                    else if (token == "thick")
                        f.stroke = 5;
                    else if (token == "solid" || token == "dotted" || token == "dashed" || token == "double") {
                    } else if (auto n = dictionary_core::CssNumeric(token, f.size, v.base_font_size_px > 0 ? v.base_font_size_px : 16.f, 0).evaluate(false))
                        f.stroke = *n;
                    else
                        f.border = color(token, f.border);
                }
            }
            f.radius = length(prop("border-radius"), i, f.size);
            constexpr std::string_view sides[]{"top","right","bottom","left"};
            auto& b=borders[i];
            for(int side=0;side<4;++side) {
                const std::string prefix="border-"+std::string(sides[side]);
                b.widths[side]=length(prop(prefix+"-width"),i,0,f.stroke);
                b.colors[side]=color(prop(prefix+"-color"),f.color);
            }
            f.stroke=*std::max_element(b.widths.begin(),b.widths.end());
            f.border=b.colors[0];
            if (prop("clip-path").starts_with("circle("))
                f.radius = f.size;
            f.padding_left = (f.flags & 512) ? length(prop("padding-left"), i, 0) : 0;
            f.padding_right = (f.flags & 512) ? length(prop("padding-right"), i, 0) : 0;
            f.padding_top = length(prop("padding-top"), i, 0);
            f.padding_bottom = length(prop("padding-bottom"), i, 0);
            auto family = prop("font-family");
            f.family_offset = index(names.size());
            f.family_bytes = index(family.size());
            names += family;
        }
        for (uint32_t i = 0; i < v.image_count; ++i)
            image_by_run[v.images[i].run] = i;
        // All logical contexts and requests are emitted before any platform measurement.
        for (uint32_t p = 0; p < v.paragraph_count; ++p) {
            const auto &input = v.paragraphs[p];
            auto &paragraph = paragraphs[p];
            paragraph.body_start = index(text.size());
            for (uint32_t r = input.first_run; r < input.first_run + input.run_count; ++r) {
                const auto &run = v.runs[r];
                if (run.role == 2)
                    continue;
                uint32_t start = index(text.size());
                append_utf16(text, {v.text + run.text_offset, run.text_bytes});
                if (run.role != 3) {
                    run_requests[r] = index(requests.size());
                    requests.push_back({run.style, start, index(text.size() - start), 0, 0});
                }
            }
            paragraph.body_count = index(text.size() - paragraph.body_start);
            contexts.push_back({input.source, paragraph.body_start, paragraph.body_count, 0});
            for (uint32_t r = input.first_run; r < input.first_run + input.run_count; ++r)
                if (run_requests[r] != none) {
                    auto &q = requests[run_requests[r]];
                    q.context_start = paragraph.body_start;
                    q.context_count = paragraph.body_count;
                }
            for (uint32_t r = input.first_run; r < input.first_run + input.run_count;) {
                if (v.runs[r].role != 2) {
                    ++r;
                    continue;
                }
                uint32_t start = index(text.size()), first = index(requests.size()), ruby = v.runs[r].ruby;
                do {
                    const auto &run = v.runs[r];
                    uint32_t at = index(text.size());
                    append_utf16(text, {v.text + run.text_offset, run.text_bytes});
                    run_requests[r] = index(requests.size());
                    requests.push_back({run.style, at, index(text.size() - at), 0, 0});
                    ++r;
                } while (r < input.first_run + input.run_count && v.runs[r].role == 2 && v.runs[r].ruby == ruby);
                auto count = index(text.size() - start);
                contexts.push_back({input.source, start, count, 1});
                for (uint32_t q = first; q < requests.size(); ++q) {
                    requests[q].context_start = start;
                    requests[q].context_count = count;
                }
            }
            if (input.marker_bytes) {
                uint32_t start = index(text.size());
                append_utf16(text, {v.text + input.marker_offset, input.marker_bytes});
                uint32_t count = index(text.size() - start);
                paragraph.marker = index(requests.size());
                requests.push_back({input.marker_style, start, count, start, count});
                contexts.push_back({input.source, start, count, 1});
            }
        }
        auto same_font = [&](uint32_t a, uint32_t b) {
            const auto &x = fonts[a], &y = fonts[b];
            return x.size == y.size && x.weight == y.weight && (x.flags & 1) == (y.flags & 1) &&
                x.letter_spacing == y.letter_spacing &&
                std::string_view(names).substr(x.family_offset, x.family_bytes) ==
                std::string_view(names).substr(y.family_offset, y.family_bytes);
        };
        for (uint32_t i = 0; i < requests.size(); ++i) {
            if (!shaping_runs.empty()) {
                auto &group = shaping_runs.back();
                const auto &first = requests[group.first_request], &previous = requests[i - 1], &next = requests[i];
                if (previous.start + previous.count == next.start && first.context_start == next.context_start &&
                    first.context_count == next.context_count && same_font(first.style, next.style)) {
                    ++group.request_count;
                    continue;
                }
            }
            shaping_runs.push_back({i, 1});
        }
        metrics.resize(requests.size(), {std::numeric_limits<float>::quiet_NaN(), 0});
        advances.resize(text.size());
        breaks.resize(text.size());
        levels.resize(text.size());
        for (const auto &r : requests)
            std::fill(advances.begin() + r.start, advances.begin() + r.start + r.count,
                      std::numeric_limits<float>::quiet_NaN());
        run_left.resize(v.run_count);
        run_right.resize(v.run_count);
        request_shift.resize(requests.size());
        request_runs.resize(requests.size(),none);
        for(uint32_t r=0;r<v.run_count;++r)if(run_requests[r]!=none)request_runs[run_requests[r]]=r;
        for (uint32_t q=0;q<requests.size();++q)request_shift[q]=fonts[requests[q].style].baseline_shift;
        paragraph_boxes.resize(v.paragraph_count);
        atomic_run_end.resize(v.run_count);
        for (size_t i=0;i<v.inline_box_count;++i) {
            const auto& b=v.inline_boxes[i];const auto& f=fonts[b.style];
            const auto display=property(v,b.style,"display");
            if(display=="inline-block" || display=="inline-flex") {
                const auto end=b.first_run+b.run_count;
                bool text_only=true;
                for(uint32_t r=b.first_run;r<end;++r)
                    if(v.runs[r].paragraph!=v.runs[b.first_run].paragraph || v.runs[r].role!=0){text_only=false;break;}
                if(text_only)atomic_run_end[b.first_run]=std::max(atomic_run_end[b.first_run],end);
            }
            run_left[b.first_run]+=f.padding_left+borders[b.style].widths[3]+length(property(v,b.style,"margin-left"),b.style,0);
            run_right[b.first_run+b.run_count-1]+=f.padding_right+borders[b.style].widths[1]+length(property(v,b.style,"margin-right"),b.style,0);
            uint32_t previous=none;
            for(uint32_t r=b.first_run;r<b.first_run+b.run_count;++r) {
                if(v.runs[r].paragraph!=previous) {
                    previous=v.runs[r].paragraph;
                    paragraph_boxes[previous].push_back(index(i));
                }
                if(run_requests[r]!=none && v.runs[r].style!=b.style)request_shift[run_requests[r]]+=f.baseline_shift;
            }
        }
    }
    void emit_box(dc_draw_command command) {
        const auto& b=borders[command.style];
        const auto& gradient=gradients[command.style];
        if(gradient.radial) {
            auto fill=command;fill.kind=3;fill.color=gradient.inner;fill.border=gradient.outer;
            fill.stroke=gradient.from;fill.radius=gradient.to;fill.flags=gradient.circle?1:0;
            commands.push_back(fill);command.color=0;
        }
        unsigned edge_count=0,last_edge=0;
        for(unsigned i=0;i<4;++i)if(b.widths[i]>0 && b.colors[i]>>24){++edge_count;last_edge=i;}
        if(!edge_count && gradient.radial)return;
        if(edge_count==1 && command.radius>0) {
            command.stroke=b.widths[last_edge];command.border=b.colors[last_edge];command.flags=1u<<(8+last_edge);
            commands.push_back(command);return;
        }
        bool uniform=true;
        for(int i=1;i<4;++i)uniform=uniform && b.widths[i]==b.widths[0] && b.colors[i]==b.colors[0];
        if(uniform){command.stroke=b.widths[0];command.border=b.colors[0];commands.push_back(command);return;}
        command.stroke=0;commands.push_back(command);
        for(int i=0;i<4;++i) {
            if(b.widths[i]<=0 || b.colors[i]>>24==0)continue;
            auto edge=command;edge.color=b.colors[i];edge.radius=0;
            if(i==0 || i==2){edge.height=b.widths[i];if(i==2)edge.y+=command.height-edge.height;}
            else {edge.width=b.widths[i];if(i==1)edge.x+=command.width-edge.width;}
            commands.push_back(edge);
        }
    }
    void pieces_for(Paragraph &p, uint32_t run) {
        uint32_t q = run_requests[run];
        if (q == none)
            return;
        const auto &request = requests[q];
        for (uint32_t at = request.start; at < request.start + request.count;) {
            uint32_t end = at + 1;
            while (end < request.start + request.count && advances[end] == 0 && text[end] != '\n' && text[at] != '\n')
                ++end;
            float width = 0;
            for (uint32_t j = at; j < end; ++j)
                width += advances[j];
            p.pieces.push_back({q, at, end - at, width});
            at = end;
        }
    }
    void dimensions(Paragraph &p, Atom &a) {
        float base_width = 0, reading_width = 0, above = 0, below = 0, reading_height = 0;
        float base_em_ascent=0,reading_em_descent=0;
        for (uint32_t k = a.first; k < a.first + a.count; ++k) {
            const auto &piece = p.pieces[k];
            const auto &f = fonts[requests[piece.request].style];
            const auto &m = metrics[piece.request];
            float half = f.line_height > 0 ? (f.line_height - (m.descent - m.ascent)) / 2 : 0;
            base_width += piece.width;
            above = std::max(above, -m.ascent + half + request_shift[piece.request]);
            a.unshifted_above=std::max(a.unshifted_above,-m.ascent+half+std::min(0.f,request_shift[piece.request]));
            below = std::max(below, m.descent + half - request_shift[piece.request]);
            base_em_ascent=std::max(base_em_ascent,m.em_ascent+request_shift[piece.request]);
        }
        for (uint32_t k = a.reading_first; k < a.reading_first + a.reading_count; ++k) {
            const auto &piece = p.pieces[k];
            const auto &f = fonts[requests[piece.request].style];
            const auto &m = metrics[piece.request];
            reading_width += piece.width;
            reading_height = std::max(reading_height, m.em_ascent+m.em_descent);
            reading_em_descent=std::max(reading_em_descent,m.em_descent);
        }
        a.width = std::max(base_width, reading_width) + a.left + a.right;
        a.above = a.reading_count ? std::max(above,base_em_ascent+reading_height) : above;
        a.base_above = above;
        a.below = below;
        a.reading_height = reading_height;
        a.reading_shift=base_em_ascent+reading_em_descent;
        if(a.reading_count && a.count) {
            const auto style=requests[p.pieces[a.first].request].style;
            if(property(v,style,"ruby-overhang")!="none" && property(v,style,"ruby-align")!="space-between") {
                float reading_font=0;
                for(uint32_t k=a.reading_first;k<a.reading_first+a.reading_count;++k)
                    reading_font=std::max(reading_font,fonts[requests[p.pieces[k].request].style].size);
                const auto extra=std::max(0.f,reading_width-base_width);
                a.overhang_start=a.overhang_end=std::min(extra/2,reading_font/2);
            }
        }
        if (a.count) {
            const auto &piece = p.pieces[a.first];
            a.level = levels[piece.start];
        }
    }
    float overhang(const Paragraph& p,const Atom& ruby,const Atom& text_atom,bool start) const {
        if(!ruby.reading_count || text_atom.reading_count || text_atom.image!=none || text_atom.forced ||
            text_atom.count!=1 || !ruby.count)return 0;
        const auto style=requests[p.pieces[text_atom.first].request].style;
        const auto base_style=requests[p.pieces[ruby.first].request].style;
        if(fonts[style].size>fonts[base_style].size || !property(v,style,"text-emphasis").empty())return 0;
        return std::min(start?ruby.overhang_start:ruby.overhang_end,text_atom.width/2);
    }
    void build_atoms(uint32_t id) {
        auto &p = paragraphs[id];
        const auto &paragraph = v.paragraphs[id];
        for (uint32_t r = paragraph.first_run; r < paragraph.first_run + paragraph.run_count;) {
            const auto &run = v.runs[r];
            if (run.role == 2) {
                ++r;
                continue;
            }
            if (run.role == 3) {
                Atom a;
                a.image = image_by_run[r];
                const auto &image = v.images[a.image];
                auto prop = [&](std::string_view key) -> std::string_view {
                    for (uint32_t j = 0; j < image.property_count; ++j) {
                        const auto &p = v.properties[image.first_property + j];
                        if (std::string_view(v.text + p.name_offset, p.name_bytes) == key)
                            return {v.text + p.value_offset, p.value_bytes};
                    }
                    return {};
                };
                float natural_w = number(prop("preferredWidth"), number(prop("width"), 100)),
                      natural_h = number(prop("preferredHeight"), number(prop("height"), 100));
                float em = prop("sizeUnits") == "em" ? fonts[run.style].size
                                                     : fonts[run.style].size / std::max(.01f, v.base_font_size_px);
                const auto container=prop("container-style");
                const auto sizing_style=container.empty()?run.style:static_cast<uint32_t>(number(container));
                a.width = std::min(widths[paragraph.source], length(property(v, sizing_style, "width"), sizing_style,
                                                                    widths[paragraph.source], natural_w * em));
                a.above = container.empty()?length(property(v, run.style, "height"), run.style, 0,
                                 a.width * natural_h / std::max(1.f, natural_w)):
                    a.width * natural_h / std::max(1.f,natural_w);
                a.legal = true;
                p.atoms.push_back(a);
                ++r;
                continue;
            }
            if(atomic_run_end[r]>r) {
                Atom a;a.first=index(p.pieces.size());
                const auto end=atomic_run_end[r];
                for(auto k=r;k<end;++k)pieces_for(p,k);
                a.count=index(p.pieces.size())-a.first;
                a.left=run_left[r];a.right=run_right[end-1];a.legal=true;
                dimensions(p,a);p.atoms.push_back(a);r=end;continue;
            }
            if (run.role == 1 && run.ruby != none && v.rubies[run.ruby].first_base_run == r) {
                const auto &ruby = v.rubies[run.ruby];
                Atom a;
                a.first = index(p.pieces.size());
                for (uint32_t k = 0; k < ruby.base_run_count; ++k)
                    pieces_for(p, r + k);
                a.count = index(p.pieces.size()) - a.first;
                a.reading_first = index(p.pieces.size());
                for (uint32_t k = 0; k < ruby.reading_run_count; ++k)
                    pieces_for(p, ruby.first_reading_run + k);
                a.reading_count = index(p.pieces.size()) - a.reading_first;
                if (a.count) {
                    const auto &last = p.pieces[a.first + a.count - 1];
                    a.legal = (breaks[last.start + last.count - 1] & 1) != 0;
                }
                a.left=run_left[r];a.right=run_right[r+ruby.base_run_count-1];
                dimensions(p, a);
                p.atoms.push_back(a);
                ++rubies;
                r += ruby.base_run_count;
                continue;
            }
            uint32_t first = index(p.pieces.size());
            pieces_for(p, r);
            for (uint32_t k = first; k < p.pieces.size(); ++k) {
                const auto &piece = p.pieces[k];
                Atom a;
                a.first = k;
                a.count = 1;
                a.legal = (breaks[piece.start + piece.count - 1] & 1) != 0;
                a.forced = text[piece.start] == '\n' || text[piece.start] == '\r';
                a.space = text[piece.start] == ' ' || text[piece.start] == '\t';
                const auto &f = fonts[run.style];
                if (k == first)
                    a.left = run_left[r];
                if (k + 1 == p.pieces.size())
                    a.right = run_right[r];
                dimensions(p, a);
                p.atoms.push_back(a);
            }
            ++r;
        }
        // CSS Text 4 interior Han punctuation spacing. The platform supplies
        // font-dependent classes; the plan chooses which blank half to remove.
        for(size_t i=1;i<p.atoms.size();++i) {
            auto& left=p.atoms[i-1];auto& right=p.atoms[i];
            if(left.count!=1 || right.count!=1 || left.reading_count || right.reading_count || left.forced || right.forced)continue;
            auto& a=p.pieces[left.first];auto& b=p.pieces[right.first];
            const auto lc=(breaks[a.start]>>1)&7,rc=(breaks[b.start]>>1)&7;
            int target=-1;
            if(lc==2 && (rc==2 || rc==3 || rc==5))target=0;
            else if(rc==1 && (lc==1 || lc==3 || lc==2 || lc==4))target=1;
            if(target<0)continue;
            if(lc==2 && rc==1 && requests[a.request].style==requests[b.request].style &&
                !text_spacing[requests[a.request].style].space_all)target=0;
            auto& piece=target?b:a;auto& atom=target?right:left;
            const auto style=requests[piece.request].style;
            const auto spacing = text_spacing[style];
            if(piece.trimmed || piece.count!=1 || spacing.space_all ||
                spacing.custom_features || piece.width<fonts[style].size*.9f)continue;
            const float trim=fonts[style].size/2;
            piece.width-=trim;atom.width-=trim;piece.trimmed=true;
        }
        float natural = 0, segment = 0;
        for (const auto &a : p.atoms) {
            if (a.forced) {
                p.max_width = std::max(p.max_width, natural);
                p.min_width = std::max(p.min_width, segment);
                natural = segment = 0;
                continue;
            }
            natural += a.width;
            segment += a.width;
            if (a.legal) {
                p.min_width = std::max(p.min_width, segment);
                segment = 0;
            }
        }
        p.max_width = std::max(p.max_width, natural);
        p.min_width = std::min(p.max_width, std::max(p.min_width, segment));
        if(p.marker!=none && property(v,paragraph.marker_style,"list-style-position")=="inside") {
            const auto& marker=requests[p.marker];float width=0;
            for(uint32_t j=marker.start;j<marker.start+marker.count;++j)width+=advances[j];
            p.max_width+=width;p.min_width+=width;
        }
    }
    void emit_text(Paragraph &p, const Piece &piece, float x, float baseline, uint32_t source, bool interactive,
                   uint32_t category = 0) {
        const auto &request = requests[piece.request];
        const auto &font = fonts[request.style];
        const auto &m = metrics[piece.request];
        baseline -= request_shift[piece.request];
        dc_draw_command command{0,
                                source,
                                piece.request,
                                piece.start,
                                piece.count,
                                request.style,
                                x,
                                baseline + m.ascent,
                                piece.width,
                                m.descent - m.ascent,
                                baseline,
                                font.color,
                                0,
                                0,
                                0,
                                static_cast<uint32_t>(levels[piece.start] & 1) | category | (interactive ? 0u : 4u) | (piece.trimmed?16u:0u)};
        if (!p.commands.empty()) {
            auto &previous = p.commands.back();
            if (previous.kind == 0 && previous.resource == command.resource &&
                previous.start + previous.count == command.start && std::abs(previous.x + previous.width - x) < .01f &&
                previous.baseline == baseline && previous.flags == command.flags) {
                previous.count += piece.count;
                previous.width += piece.width;
            } else
                p.commands.push_back(command);
        } else
            p.commands.push_back(command);
        if (interactive && piece.width > 0 && text[piece.start] != ' ' && text[piece.start] != '\n')
            p.hits.push_back(
                {source, piece.request, piece.start, x, baseline + m.ascent, piece.width, m.descent - m.ascent});
    }
    void emit_group(Paragraph &p, uint32_t first, uint32_t count, float x, float width, float baseline, uint32_t source,
                    bool interactive, bool distribute, bool annotation = false) {
        float natural = 0;
        for (uint32_t i = first; i < first + count; ++i)
            natural += p.pieces[i].width;
        float extra = std::max(0.f, width - natural), gap = distribute && count > 1 ? extra / count : 0;
        x += gap > 0 ? gap / 2 : extra / 2;
        for (uint32_t i = first; i < first + count; ++i) {
            emit_text(p, p.pieces[i], x, baseline, source, interactive, annotation ? 2u : distribute ? 8u : 0u);
            x += p.pieces[i].width + gap;
        }
    }
    dc_text_geometry wrap(uint32_t id, float width) {
        auto &p = paragraphs[id];
        p.commands.clear();
        p.hits.clear();
        const auto &input = v.paragraphs[id];
        const auto &f = fonts[input.style];
        float y = 0;
        uint32_t line_count = 0;
        size_t from = 0;
        float indent = length(property(v, input.style, "text-indent"), input.style, width);
        const bool inside_marker=p.marker!=none && property(v,input.marker_style,"list-style-position")=="inside";
        float marker_width=0;
        if(inside_marker) {
            const auto& marker=requests[p.marker];
            for(uint32_t j=marker.start;j<marker.start+marker.count;++j)marker_width+=advances[j];
        }
        do {
            size_t end = from, last = from;
            float used = 0;
            const float line_indent = line_count ? 0 : indent+marker_width;
            for (; end < p.atoms.size(); ++end) {
                const auto &a = p.atoms[end];
                if (a.forced)
                    break;
                const auto shared=end>from?overhang(p,a,p.atoms[end-1],true)+overhang(p,p.atoms[end-1],a,false):0;
                const bool overflow = (f.flags & 256 || !a.space)
                    ? (used + a.width - shared > std::max(1.f, width - line_indent))
                    : (used > std::max(1.f, width - line_indent));
                if (!(f.flags & 16) && overflow && end > from) {
                    if (last > from)
                        end = last;
                    break;
                }
                used += a.width-shared;
                if (a.legal)
                    last = end + 1;
            }
            size_t visible = end;
            if (!(f.flags & 256))
                while (visible > from && p.atoms[visible - 1].space)
                    --visible;
            used = 0;
            float above = f.size * .8f, below = f.size * .2f;
            for (size_t i = from; i < visible; ++i) {
                used += p.atoms[i].width;
                if(i>from)used-=overhang(p,p.atoms[i],p.atoms[i-1],true)+overhang(p,p.atoms[i-1],p.atoms[i],false);
                // First-line annotations in cells overhang the content edge into padding;
                // subsequent lines reserve room so annotations cannot hit the prior line.
                const auto& atom=p.atoms[i];
                const bool first_source=!id || v.paragraphs[id-1].source!=input.source;
                const bool first_cell_ruby=!line_count && atom.reading_count &&
                    (input.cell!=none || (first_source && (f.flags&512)));
                // The body capture starts at its first base line; raised header ink
                // may overhang that edge instead of translating the entire document.
                const bool first_source_shift=input.cell==none && !line_count && !atom.reading_count && atom.image==none &&
                    first_source;
                above = std::max(above, first_cell_ruby?atom.base_above:first_source_shift?atom.unshifted_above:atom.above);
                below = std::max(below, p.atoms[i].below);
            }
            float leading = std::max(0.f, f.line_height - above - below) / 2;
            float baseline = y + leading + above;
            float x = line_indent + ((f.flags & 32)   ? (width - line_indent - used) / 2
                                     : (f.flags & 64) ? width - line_indent - used
                                                      : 0);
            std::vector<size_t> visual;
            visual.reserve(visible - from);
            uint8_t highest = 0, lowest_odd = 255;
            for (size_t i = from; i < visible; ++i) {
                visual.push_back(i);
                highest = std::max(highest, p.atoms[i].level);
                if (p.atoms[i].level & 1)
                    lowest_odd = std::min(lowest_odd, p.atoms[i].level);
            }
            // Unicode bidi L2: reverse sequences from highest level down to lowest odd.
            for (int level = highest; level >= lowest_odd; --level)
                for (size_t a = 0; a < visual.size();) {
                    if (p.atoms[visual[a]].level < level) {
                        ++a;
                        continue;
                    }
                    size_t b = a + 1;
                    while (b < visual.size() && p.atoms[visual[b]].level >= level)
                        ++b;
                    std::reverse(visual.begin() + a, visual.begin() + b);
                    a = b;
                }
            for (auto i : visual) {
                const auto &a = p.atoms[i];
                const auto start_over=i>from?overhang(p,a,p.atoms[i-1],true):0;
                const auto end_over=i+1<visible?overhang(p,a,p.atoms[i+1],false):0;
                if (a.image != none)
                    p.commands.push_back({2, input.source, a.image, 0, 0, v.runs[v.images[a.image].run].style, x,
                                          baseline - a.above, a.width, a.above + a.below, baseline, 0, 0, 0, 0, 0});
                else {
                    float content = a.width - a.left - a.right;
                    emit_group(p, a.first, a.count, x-start_over + a.left, content, baseline, input.source, input.cell == none,
                               a.reading_count > 0);
                    if (a.reading_count) {
                        emit_group(p, a.reading_first, a.reading_count, x-start_over + a.left, content,
                                   baseline - a.reading_shift, input.source, false, true, true);
                    }
                }
                x += a.width-start_over-end_over;
            }
            if (!line_count && p.marker != none) {
                const auto &r = requests[p.marker];
                float w = 0;
                for (uint32_t j = r.start; j < r.start + r.count; ++j)
                    w += advances[j];
                emit_text(p, {p.marker, r.start, r.count, w}, inside_marker?0:-w - 4, baseline, input.source, false);
            }
            y += above + below + leading * 2;
            ++line_count;
            if (end < p.atoms.size() && p.atoms[end].forced)
                ++end;
            if (end == from && end < p.atoms.size())
                ++end;
            from = end;
            if (!(f.flags & 256))
                while (from < p.atoms.size() && p.atoms[from].space)
                    ++from;
        } while (from < p.atoms.size());
        lines += line_count;
        return {width, y, line_count};
    }
};
namespace {
int prepare(void *ptr, const dc_prepared_view *, uint32_t id, uint32_t, dc_text_measurement *out, dc_error *) {
    auto &d = *static_cast<dc_drawing_plan *>(ptr);
    *out = {id, d.paragraphs[id].min_width, d.paragraphs[id].max_width};
    return 0;
}
int wrap(void *ptr, uint64_t id, float width, dc_text_geometry *out, dc_error *e) {
    try {
        *out = static_cast<dc_drawing_plan *>(ptr)->wrap(static_cast<uint32_t>(id), width);
        return 0;
    } catch (const std::exception &ex) {
        std::snprintf(e->message, sizeof(e->message), "%s", ex.what());
        return 1;
    }
}
void release(void *, uint64_t) {}
} // namespace
int dc_drawing_begin(const dc_prepared_document *input, const float *widths, size_t count, uint32_t fg, uint32_t border,
                     dc_drawing_plan **output, dc_error *error) {
    if (output)
        *output = nullptr;
    try {
        if (!input || !output || (!widths && count))
            throw std::invalid_argument("Invalid drawing inputs");
        for (size_t i = 0; i < count; ++i)
            if (!std::isfinite(widths[i]) || widths[i] <= 0)
                throw std::invalid_argument("Invalid drawing width");
        *output = new dc_drawing_plan(input, widths, count, fg, border);
        return 0;
    } catch (const std::exception &e) {
        if (error)
            std::snprintf(error->message, sizeof(error->message), "%s", e.what());
        return 1;
    }
}
dc_drawing_view dc_drawing_plan_view(dc_drawing_plan *d) {
    if (!d)
        return {};
    return {d->fonts.data(),    d->fonts.size(),    d->names.data(),    d->names.size(),    d->text.data(),
            d->text.size(),     d->requests.data(), d->requests.size(), d->contexts.data(), d->contexts.size(),
            d->metrics.data(),  d->advances.data(), d->breaks.data(),   d->levels.data(),   d->commands.data(),
            d->commands.size(), d->hits.data(),     d->hits.size(),     d->sources.data(),  d->sources.size(),
            d->lines,           d->rubies, d->shaping_runs.data(), d->shaping_runs.size()};
}
int dc_drawing_finish(dc_drawing_plan *d, dc_error *error) {
    try {
        if (!d || d->finished)
            throw std::invalid_argument("Drawing finish is single-use");
        d->finished = true;
        for (const auto &m : d->metrics)
            if (!std::isfinite(m.ascent) || !std::isfinite(m.descent) || m.descent < m.ascent ||
                !std::isfinite(m.em_ascent) || !std::isfinite(m.em_descent) || m.em_ascent<0 || m.em_descent<0)
                throw std::invalid_argument("Missing/invalid bulk font metrics");
        for (float n : d->advances)
            if (!std::isfinite(n) || n < 0)
                throw std::invalid_argument("Missing/invalid bulk text advances");
        for (uint32_t i = 0; i < d->paragraphs.size(); ++i)
            d->build_atoms(i);
        dc_text_engine engine{d, prepare, wrap, release};
        dc_layout_document *raw = nullptr;
        if (dc_layout(d->input, &engine, d->widths.data(), d->widths.size(), &raw, error))
            return 1;
        std::unique_ptr<dc_layout_document, decltype(&dc_layout_document_destroy)> layout(raw,
                                                                                          dc_layout_document_destroy);
        const auto geometry = dc_layout_document_view(raw);
        d->sources.resize(geometry.source_count);
        for (uint32_t s = 0; s < geometry.source_count; ++s) {
            auto &source = d->sources[s];
            source.width = geometry.sources[s].width;
            source.height = geometry.sources[s].height;
            source.first_command = index(d->commands.size());
            source.first_hit = index(d->hits.size());
            auto blocks = [&](bool in_cell) {
                for (size_t i = 0; i < geometry.block_count; ++i) {
                    const auto &b = geometry.blocks[i];
                    if (b.source != s || (b.cell != none) != in_cell)
                        continue;
                    const auto &f = d->fonts[b.style];
                    if (b.height > 0 && (f.background || f.stroke))
                        d->emit_box({1, s, 0, 0, 0, b.style, b.x, b.y, b.width, b.height, 0, f.background,
                                               f.border, f.stroke, f.radius, 0});
                }
            };
            blocks(false);
            struct CellEdge {float start,end,width;uint32_t color,style;};
            std::map<std::tuple<uint32_t,bool,float>,std::vector<CellEdge>> collapsed_edges;
            for (size_t i = 0; i < geometry.cell_count; ++i) {
                const auto &c = geometry.cells[i];
                const auto table=d->v.rows[d->v.cells[c.cell].row].table;
                if (d->v.tables[table].source != s)
                    continue;
                auto style = d->v.cells[c.cell].style;
                const auto &f = d->fonts[style];
                const auto table_style=d->v.tables[table].style;
                if(d->property(d->v,table_style,"border-collapse")=="collapse" && d->property(d->v,table_style,"display")!="grid") {
                    if(f.background)d->commands.push_back({1,s,0,0,0,style,c.x,c.y,c.width,c.height,0,f.background,0,0,0,0});
                    const auto& border=d->borders[style];
                    for(int edge=0;edge<4;++edge) {
                        if(border.widths[edge]<=0 || !(border.colors[edge]>>24))continue;
                        const bool horizontal=edge==0 || edge==2;
                        const float coordinate=horizontal?c.y+(edge==2?c.height:0):c.x+(edge==1?c.width:0);
                        const float start=horizontal?c.x:c.y;
                        collapsed_edges[{table,horizontal,coordinate}].push_back({start,start+(horizontal?c.width:c.height),
                            border.widths[edge],border.colors[edge],style});
                    }
                } else if (f.background || f.stroke)
                    d->emit_box({1, s, 0, 0, 0, style, c.x, c.y, c.width, c.height, 0, f.background, f.border,
                                           f.stroke, f.radius, 0});
            }
            // Split shared edges at span boundaries and paint the winning border once.
            for(const auto& [key,edges]:collapsed_edges) {
                const auto [table,horizontal,coordinate]=key;
                std::vector<float> endpoints;
                for(const auto& edge:edges){endpoints.push_back(edge.start);endpoints.push_back(edge.end);}
                std::sort(endpoints.begin(),endpoints.end());
                endpoints.erase(std::unique(endpoints.begin(),endpoints.end()),endpoints.end());
                for(size_t i=1;i<endpoints.size();++i) {
                    const CellEdge* winner=nullptr;
                    for(const auto& edge:edges)
                        if(edge.start<=endpoints[i-1] && edge.end>=endpoints[i] && (!winner || edge.width>winner->width))winner=&edge;
                    if(!winner)continue;
                    const float start=endpoints[i-1],extent=endpoints[i]-start;
                    d->commands.push_back({1,s,0,0,0,winner->style,
                        horizontal?start:coordinate-winner->width/2,horizontal?coordinate-winner->width/2:start,
                        horizontal?extent:winner->width,horizontal?winner->width:extent,0,winner->color,0,0,0,0});
                }
            }
            blocks(true);
            for (size_t i = 0; i < geometry.paragraph_count; ++i) {
                const auto &position = geometry.paragraphs[i];
                if (position.source != s)
                    continue;
                const auto &paragraph = d->paragraphs[position.paragraph];
                // Paint ancestor fragments behind descendants, retaining independent padding/borders.
                const auto& boxes=d->paragraph_boxes[position.paragraph];
                for(auto it=boxes.rbegin();it!=boxes.rend();++it) {
                    const auto& box=d->v.inline_boxes[*it];const auto& f=d->fonts[box.style];
                    if(!f.background && !f.stroke)continue;
                    float left=0,right=0,baseline=std::numeric_limits<float>::quiet_NaN(),top=0,bottom=0;
                    auto emit=[&]() {
                        if(!std::isfinite(baseline))return;
                        d->emit_box({1,s,0,0,0,box.style,left-f.padding_left-d->borders[box.style].widths[3]+position.x,
                            top-f.padding_top-d->borders[box.style].widths[0]+position.y,
                            right-left+f.padding_left+f.padding_right+d->borders[box.style].widths[3]+d->borders[box.style].widths[1],
                            bottom-top+f.padding_top+f.padding_bottom+d->borders[box.style].widths[0]+d->borders[box.style].widths[2],0,f.background,f.border,f.stroke,f.radius,0});
                    };
                    for(const auto& c:paragraph.commands) {
                        if(c.kind!=0 || c.flags&2)continue;
                        const auto run=d->request_runs[c.resource];
                        if(run<box.first_run || run>=box.first_run+box.run_count)continue;
                        float line=c.baseline+d->request_shift[c.resource];
                        if(std::isfinite(baseline) && std::abs(line-baseline)>.01f){emit();baseline=std::numeric_limits<float>::quiet_NaN();}
                        const auto& m=d->metrics[c.resource];
                        const float scale=f.size/d->fonts[c.style].size;
                        if(!std::isfinite(baseline)) {
                            baseline=line;left=c.x;right=c.x+c.width;
                            top=c.baseline+m.ascent*scale;bottom=c.baseline+m.descent*scale;
                        } else {left=std::min(left,c.x);right=std::max(right,c.x+c.width);}
                    }
                    emit();
                }
                for (auto command : paragraph.commands) {
                    command.x += position.x;
                    if(command.kind==0 && command.resource==paragraph.marker)command.x+=position.marker_x-position.x;
                    command.y += position.y;
                    command.baseline += position.y;
                    d->commands.push_back(command);
                }
                for (auto hit : paragraph.hits) {
                    hit.x += position.x;
                    hit.y += position.y;
                    d->hits.push_back(hit);
                }
            }
            source.command_count = index(d->commands.size()) - source.first_command;
            source.hit_count = index(d->hits.size()) - source.first_hit;
        }
        return 0;
    } catch (const std::exception &e) {
        if (error)
            std::snprintf(error->message, sizeof(error->message), "%s", e.what());
        return 1;
    }
}
void dc_drawing_plan_destroy(dc_drawing_plan *d) { delete d; }

float dc_card_place(const float *parts, size_t count, float *rectangles) {
    float y = 0;
    for (size_t i = 0; i < count; ++i) {
        const auto *p = parts + i * 6;
        auto *r = rectangles + i * 4;
        r[0] = p[2];
        r[1] = y;
        r[2] = p[1];
        r[3] = 0;
        if (p[5] == 0) continue;
        y += p[3];
        r[1] = y;
        r[3] = p[0];
        y += p[0] + p[4];
    }
    return y;
}
