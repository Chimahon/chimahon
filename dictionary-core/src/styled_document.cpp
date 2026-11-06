#include "styled_document.h"
#include "resolved_style.hpp"
#include "document_storage.hpp"
#include "css_numeric.hpp"
#include "css_nesting.hpp"
#include <lexbor/style/style.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <charconv>
#include <cstdio>
#include <map>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>

namespace {
using namespace dictionary_core;
constexpr uint32_t none = UINT32_MAX;
using Properties = std::map<std::string, std::string, std::less<>>;
const lxb_char_t* bytes(std::string_view s) { return reinterpret_cast<const lxb_char_t*>(s.data()); }
void require_status(lxb_status_t status, const char* operation) {
    if (status != LXB_STATUS_OK) throw std::runtime_error(operation);
}
std::string_view trim(std::string_view s) {
    auto start = s.find_first_not_of(" \t\n\r");
    if (start == s.npos) return {};
    return s.substr(start, s.find_last_not_of(" \t\n\r") - start + 1);
}
std::string kebab(std::string_view s) {
    std::string result;
    for (char c : s) {
        if (c >= 'A' && c <= 'Z') { result += '-'; result += static_cast<char>(c + 32); }
        else result += c;
    }
    return result;
}
bool inherited(std::string_view name) {
    return name.starts_with("--") || name.starts_with("font-") || name == "text-align" || name == "text-indent" || name == "text-transform" ||
        name == "color" || name == "line-height" || name == "white-space" || name == "visibility" ||
        name == "direction" || name == "writing-mode" || name == "word-break" || name == "word-spacing" ||
        name == "letter-spacing" || name == "text-spacing-trim" || name.starts_with("list-style") || name.starts_with("ruby-");
}
std::string_view property(const Properties& p, std::string_view name) {
    auto it = p.find(name); return it == p.end() ? std::string_view{} : std::string_view(it->second);
}
bool space(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f'; }

struct Dom {
    lxb_html_document_t* value = nullptr;
    Dom() {
        value = lxb_html_document_create();
        if (!value) throw std::bad_alloc();
        try {
            require_status(lxb_style_init(value), "Lexbor style initialization failed");
            constexpr std::string_view shell = "<!doctype html><html><head></head><body></body></html>";
            require_status(lxb_html_document_parse(value, bytes(shell), shell.size()), "DOM initialization failed");
        } catch (...) { lxb_style_destroy(value); lxb_html_document_destroy(value); throw; }
    }
    ~Dom() { lxb_style_destroy(value); lxb_html_document_destroy(value); }
};
lxb_dom_element_t* element(Dom& doc, std::string_view name) {
    auto result = lxb_dom_document_create_element(lxb_dom_interface_document(doc.value), bytes(name), name.size(), nullptr);
    if (!result) throw std::runtime_error("Cannot create structured element");
    return result;
}
void attribute(lxb_dom_element_t* node, std::string_view name, std::string_view value) {
    if (!lxb_dom_element_set_attribute(node, bytes(name), name.size(), bytes(value), value.size()))
        throw std::runtime_error("Cannot assign structured attribute");
}
void append(lxb_dom_node_t* parent, lxb_dom_node_t* child) {
    if (lxb_dom_node_append_child(parent, child) != LXB_DOM_EXCEPTION_OK)
        throw std::runtime_error("Cannot append structured node");
}
uint32_t find(const Storage& s, uint32_t object, std::string_view name) {
    if (s.tokens[object].kind != Kind::object) return none;
    for (auto at = object + 1; at < s.tokens[object].end;) {
        const auto key = at++;
        if (s.value(key) == name) return at;
        at = s.tokens[at].end;
    }
    return none;
}
std::string scalar(const Storage& s, uint32_t at) {
    if (at == none) return {};
    const auto kind = s.tokens[at].kind;
    if (kind == Kind::string || kind == Kind::number || kind == Kind::boolean) return std::string(s.value(at));
    return {};
}
uint32_t positive(const Storage& s, uint32_t object, std::string_view name) {
    auto value = scalar(s, find(s, object, name));
    uint32_t n = 1;
    std::from_chars(value.data(), value.data() + value.size(), n);
    return std::clamp(n, 1u, 1000u);
}

std::string inline_css(const Storage& s, uint32_t object) {
    const auto data = s.fields(object)[static_cast<uint8_t>(Field::style)];
    if (data == none || s.tokens[data].kind != Kind::object) return {};
    constexpr std::string_view supported = "|font-style|font-weight|color|background|background-color|text-decoration-style|text-decoration-color|text-decoration|text-decoration-line|border-color|border-style|border-radius|border-width|clip-path|vertical-align|text-align|text-emphasis|text-shadow|margin|padding|padding-top|padding-left|padding-right|padding-bottom|white-space|word-break|cursor|list-style-type|";
    std::string result;
    for (auto at = data + 1; at < s.tokens[data].end;) {
        const auto key = at++;
        auto name = kebab(s.value(key));
        auto value = scalar(s, at);
        const bool side = name == "margin-top" || name == "margin-right" || name == "margin-bottom" || name == "margin-left";
        if (name == "text-decoration-line" && s.tokens[at].kind == Kind::array) {
            for (auto item = at + 1; item < s.tokens[at].end; item = s.tokens[item].end) {
                if (!value.empty()) value += ' ';
                value += scalar(s, item);
            }
        } else if (s.tokens[at].kind != Kind::string && !(side && s.tokens[at].kind == Kind::number)) {
            at = s.tokens[at].end;
            continue;
        }
        if (side && s.tokens[at].kind == Kind::number) value += "em";
        if ((side || supported.find("|" + name + "|") != supported.npos) && !value.empty())
            result += name + ":" + value + ";";
        at = s.tokens[at].end;
    }
    return result;
}
void copy_attributes(const Storage& s, uint32_t object, lxb_dom_element_t* target) {
    if (s.tokens[object].kind != Kind::object) return;
    const auto html=find(s,object,"htmlAttributes");
    if(html!=none && s.tokens[html].kind==Kind::object)
        for(auto at=html+1;at<s.tokens[html].end;) {
            const std::string name(s.value(at++));const std::string value(s.value(at));
            attribute(target,name,value);at=s.tokens[at].end;
        }
    const auto fields = s.fields(object);
    const auto data = fields[static_cast<uint8_t>(Field::data)];
    if (data != none && s.tokens[data].kind == Kind::object) {
        for (auto at = data + 1; at < s.tokens[data].end;) {
            const auto key = at++;
            const auto name = s.value(key);
            // DOMStringMap rejects a hyphen followed by a lower-case ASCII letter.
            bool valid = true;
            for (size_t i = 0; i + 1 < name.size(); ++i)
                if (name[i] == '-' && name[i+1] >= 'a' && name[i+1] <= 'z') valid = false;
            if (valid && s.tokens[at].kind != Kind::null_value) {
                std::string mapped = "data-sc";
                if (!name.empty() && name.front() >= 'a' && name.front() <= 'z') mapped += '-';
                mapped += kebab(name);
                attribute(target, mapped, scalar(s, at));
            }
            at = s.tokens[at].end;
        }
    }
    for (std::string_view name : {"href", "title", "lang", "open", "colSpan", "rowSpan"}) {
        const auto at = find(s, object, name);
        if (at != none && s.tokens[at].kind != Kind::null_value) {
            if (name == "open" && scalar(s, at) != "true") continue;
            std::string mapped(name);
            std::transform(mapped.begin(), mapped.end(), mapped.begin(), [](unsigned char c){return static_cast<char>(std::tolower(c));});
            attribute(target, mapped, scalar(s, at));
        }
    }
    const auto style = inline_css(s, object);
    if (!style.empty()) attribute(target, "style", style);
}
lxb_status_t serialize(const lxb_char_t* data, size_t length, void* context) {
    try { static_cast<std::string*>(context)->append(reinterpret_cast<const char*>(data), length); }
    catch (...) { return LXB_STATUS_ERROR_MEMORY_ALLOCATION; }
    return LXB_STATUS_OK;
}
struct Declaration { std::string_view value; uint32_t specificity; size_t offset; };
struct DeclarationCollector {
    std::map<std::string_view,Declaration,std::less<>> declarations;
    std::unordered_map<const lxb_css_rule_declaration_t*, std::pair<std::string,std::string>>& cache;
};
lxb_status_t declaration(lxb_dom_element_t*, const lxb_css_rule_declaration_t* value, void* context,
                         lxb_css_selector_specificity_t specificity, bool) {
    try {
        auto& collector = *static_cast<DeclarationCollector*>(context);
        auto it = collector.cache.find(value);
        if (it == collector.cache.end()) {
            std::string name, text;
            require_status(lxb_css_property_serialize_name(value->u.user, static_cast<lxb_css_property_type_t>(value->type), serialize, &name), "Property name serialization failed");
            require_status(lxb_css_property_serialize(value->u.user, static_cast<lxb_css_property_type_t>(value->type), serialize, &text), "Property serialization failed");
            it = collector.cache.emplace(value, std::make_pair(std::move(name), std::move(text))).first;
        }
        // Lexbor stores every declaration its typed parser rejected (var(), etc.) under the single
        // __UNDEF id, so a later declaration for one property evicts earlier ones into the weak
        // chain. Rank every callback, including weak ones, to recover the real winner per name.
        const std::pair<uint32_t,size_t> rank{specificity,value->offset.name_begin};
        auto found = collector.declarations.find(it->second.first);
        if (found != collector.declarations.end() && !(std::pair{found->second.specificity,found->second.offset} < rank))
            return LXB_STATUS_OK;
        collector.declarations[it->second.first] = {it->second.second, rank.first, rank.second};
        return LXB_STATUS_OK;
    } catch (...) { return LXB_STATUS_ERROR; }
}
void expand_spacing(Properties& computed,const DeclarationCollector& collector) {
    for(std::string_view group:{"margin","padding"}) {
        std::vector<std::pair<std::pair<uint32_t,size_t>,std::string_view>> ordered;
        for(const auto& [name,decl]:collector.declarations)if(name==group || (name.size()>group.size() && name.starts_with(group) && name[group.size()]=='-'))ordered.emplace_back(std::pair{decl.specificity,decl.offset},name);
        std::sort(ordered.begin(),ordered.end());
        std::array<std::string,4> sides{};
        const bool rtl=property(computed,"direction")=="rtl";
        for(const auto& [rank,name]:ordered) {
            const auto value=property(computed,name);if(value.empty())continue;
            std::vector<std::string_view> tokens;
            size_t at=0;
            while(at<value.size()) {
                while(at<value.size() && space(value[at]))++at;
                const auto first=at;unsigned depth=0;
                for(;at<value.size();++at){if(value[at]=='(')++depth;if(value[at]==')' && depth)--depth;if(space(value[at]) && depth==0)break;}
                if(at>first)tokens.emplace_back(value.substr(first,at-first));
            }
            if(tokens.empty() || tokens.size()>4)continue;
            auto assign=[&](unsigned side,unsigned token){sides[side]=tokens[std::min<size_t>(token,tokens.size()-1)];};
            const auto suffix=name.substr(group.size());
            if(suffix.empty()) {assign(0,0);assign(1,tokens.size()>1?1:0);assign(2,tokens.size()>2?2:0);assign(3,tokens.size()>3?3:tokens.size()>1?1:0);}
            else if(suffix=="-top")assign(0,0);else if(suffix=="-right")assign(1,0);else if(suffix=="-bottom")assign(2,0);else if(suffix=="-left")assign(3,0);
            else if(suffix=="-block"){assign(0,0);assign(2,1);}
            else if(suffix=="-inline"){assign(rtl?1:3,0);assign(rtl?3:1,1);}
            else if(suffix=="-block-start")assign(0,0);else if(suffix=="-block-end")assign(2,0);
            else if(suffix=="-inline-start")assign(rtl?1:3,0);else if(suffix=="-inline-end")assign(rtl?3:1,0);
        }
        if(ordered.empty())continue;
        for(const auto& [rank,name]:ordered)if(auto found=computed.find(name);found!=computed.end())computed.erase(found);
        constexpr std::string_view names[]{"-top","-right","-bottom","-left"};
        for(unsigned i=0;i<4;++i)if(!sides[i].empty())computed[std::string(group)+std::string(names[i])]=std::move(sides[i]);
    }
}
std::vector<std::string> css_words(std::string_view value) {
    std::vector<std::string> words;size_t at=0;
    while(at<value.size()) {
        while(at<value.size() && space(value[at]))++at;
        const auto start=at;unsigned depth=0;
        while(at<value.size()) {
            if(value[at]=='(')++depth;
            if(value[at]==')' && depth)--depth;
            if(space(value[at]) && depth==0)break;
            ++at;
        }
        if(at>start)words.emplace_back(value.substr(start,at-start));
    }
    return words;
}
void expand_borders(Properties& computed,const DeclarationCollector& collector) {
    std::vector<std::pair<std::pair<uint32_t,size_t>,std::string_view>> ordered;
    for(const auto& [name,decl]:collector.declarations)
        if(name=="border" || (name.starts_with("border-") && name.find("radius")==name.npos))ordered.emplace_back(std::pair{decl.specificity,decl.offset},name);
    if(ordered.empty())return;
    std::sort(ordered.begin(),ordered.end());
    struct Side {std::string width="medium",style="none",color="currentcolor";};
    std::array<Side,4> sides;
    constexpr std::string_view names[]{"top","right","bottom","left"};
    for(const auto& [rank,name]:ordered) {
        const auto value=property(computed,name);auto tokens=css_words(value);if(tokens.empty())continue;
        std::string_view suffix=name=="border"?std::string_view{}:name.substr(7);
        int side=-1;
        for(int i=0;i<4;++i)if(suffix==names[i] || suffix.starts_with(std::string(names[i])+"-")) {
            side=i;suffix=suffix.size()==names[i].size()?std::string_view{}:suffix.substr(names[i].size()+1);break;
        }
        auto assign=[&](Side& target,const std::string& token) {
            if(suffix=="width")target.width=token;
            else if(suffix=="style")target.style=token;
            else if(suffix=="color")target.color=token;
        };
        if(!suffix.empty()) {
            if(side>=0)assign(sides[side],tokens[0]);
            else if(suffix=="width" || suffix=="style" || suffix=="color") {
                const size_t indices[]{0,tokens.size()>1?1u:0u,tokens.size()>2?2u:0u,tokens.size()>3?3u:tokens.size()>1?1u:0u};
                for(int i=0;i<4;++i)assign(sides[i],tokens[indices[i]]);
            }
        } else {
            Side shorthand;
            for(const auto& token:tokens) {
                if(token=="none" || token=="hidden" || token=="solid" || token=="dotted" || token=="dashed" || token=="double" ||
                    token=="groove" || token=="ridge" || token=="inset" || token=="outset")shorthand.style=token;
                else if(token=="thin" || token=="medium" || token=="thick" || CssNumeric(token,16,16,0).evaluate(false))shorthand.width=token;
                else shorthand.color=token;
            }
            if(side>=0)sides[side]=shorthand;else sides.fill(shorthand);
        }
    }
    for(int i=0;i<4;++i) {
        const std::string prefix="border-"+std::string(names[i]);
        computed[prefix+"-width"]=(sides[i].style=="none" || sides[i].style=="hidden")?"0px":
            sides[i].width=="thin"?"1px":sides[i].width=="medium"?"3px":sides[i].width=="thick"?"5px":sides[i].width;
        computed[prefix+"-style"]=sides[i].style;computed[prefix+"-color"]=sides[i].color;
    }
}
std::string resolve_vars(std::string_view input, const Properties& properties, unsigned depth, uint64_t& unresolved) {
    if (depth > 32) { ++unresolved; return std::string(input); }
    std::string out;
    size_t at = 0;
    while (at < input.size()) {
        const auto begin = input.find("var(", at);
        if (begin == input.npos) { out.append(input.substr(at)); break; }
        out.append(input.substr(at, begin - at));
        size_t end = begin + 4, comma = input.npos;
        unsigned nesting = 1;
        for (; end < input.size() && nesting; ++end) {
            if (input[end] == '(') ++nesting;
            if (input[end] == ')') --nesting;
            if (input[end] == ',' && nesting == 1 && comma == input.npos) comma = end;
        }
        if (nesting) { ++unresolved; out.append(input.substr(begin)); break; }
        const auto key_end = comma == input.npos ? end - 1 : comma;
        const auto key = trim(input.substr(begin + 4, key_end - begin - 4));
        auto found = properties.find(key);
        if (found != properties.end()) out += resolve_vars(found->second, properties, depth + 1, unresolved);
        else if (comma != input.npos) out += resolve_vars(trim(input.substr(comma + 1, end - comma - 2)), properties, depth + 1, unresolved);
        else { ++unresolved; out.append(input.substr(begin, end - begin)); }
        at = end;
    }
    return out;
}
float font_size(std::string_view value, float parent, float root, uint64_t& unresolved) {
    if (value.empty() || value == "inherit") return parent;
    if (value == "smaller") return parent / 1.2f;
    if (value == "larger") return parent * 1.2f;
    if (auto result = CssNumeric(value,parent,root>0?root:16.f,parent).evaluate(false); result && *result >= 0) return *result;
    ++unresolved; return parent;
}
std::string default_display(std::string_view tag) {
    if (tag == "table") return "table";
    if (tag == "tr") return "table-row";
    if (tag == "td" || tag == "th") return "table-cell";
    if (tag == "li") return "list-item";
    if (tag == "summary") return "list-item";
    if (tag == "div" || tag == "p" || tag == "ul" || tag == "ol" || tag == "details" || tag == "summary" || tag == "section") return "block";
    if (tag == "rp") return "none";
    return "inline";
}
}

struct RuleCandidate {
    lxb_css_rule_style_t* rule;
    std::vector<std::vector<std::string>> alternatives;
};
struct GeneratedRule { size_t candidate; lxb_css_selector_list_t* list; };
struct dc_stylesheets {
    std::atomic<uint32_t> refs{1};
    lxb_css_parser_t* parser = nullptr;
    std::vector<lxb_css_stylesheet_t*> sheets;
    std::vector<uint64_t> unsupported_rules;
    std::vector<std::vector<RuleCandidate>> candidates;
    std::vector<std::vector<GeneratedRule>> generated;
    bool verify_candidates = false;
    ~dc_stylesheets() {
        for (auto* sheet : sheets) lxb_css_stylesheet_destroy(sheet, true);
        if (parser) lxb_css_parser_destroy(parser, true);
    }
};
/* A popup open parses the same stylesheet set once per preparation worker, and
   again on every later open. The parsed sheets are read-only while compiling and
   are never referenced by a prepared document, so they can be kept and shared. */
void release_sheets(dc_stylesheets* sheets) {
    if(sheets && sheets->refs.fetch_sub(1)==1) delete sheets;
}
uint64_t stylesheet_key(const dc_css_source* sources,size_t count) {
    uint64_t hash=14695981039346656037ull;
    auto mix=[&](const void* data,size_t size) {
        auto* p=static_cast<const uint8_t*>(data);
        for(size_t i=0;i<size;++i){hash^=p[i];hash*=1099511628211ull;}
    };
    mix(&count,sizeof count);
    for(size_t i=0;i<count;++i){mix(&sources[i].bytes,sizeof sources[i].bytes);mix(sources[i].utf8,sources[i].bytes);}
    return hash;
}
struct SheetCache {
    std::mutex mutex;
    std::vector<std::pair<uint64_t,dc_stylesheets*>> entries;
    ~SheetCache() { for(auto& entry:entries) release_sheets(entry.second); }
};
SheetCache& sheet_cache() { static SheetCache cache; return cache; }
struct StyleInfo {
    std::string_view display, white_space, list_style_type;
    int8_t box_extra = -1; /* -1 unknown, 0 none, 1 non-zero box property */
};
struct dc_prepared_document {
    std::string text;
    std::vector<dc_text_run> runs;
    std::vector<dc_paragraph_input> paragraphs;
    std::vector<dc_ruby_input> rubies;
    std::vector<dc_table_input> tables;
    std::vector<dc_row_input> rows;
    std::vector<dc_cell_input> cells;
    std::vector<dc_style_input> styles;
    std::vector<dc_style_property> properties;
    std::vector<dc_flow_input> flow;
    std::vector<dc_image_input> images;
    std::vector<dc_inline_box> inline_boxes;
    std::vector<Properties> computed;
    std::unique_ptr<dictionary_core::ResolvedStyles> resolved;
    std::vector<StyleInfo> info;
    std::vector<float> font_sizes;
    std::unordered_map<std::string, uint32_t> interned;
    std::unordered_map<std::string, uint32_t> resolved_styles;
    float box_extra_base = -1;
    bool has_box_extra(uint32_t style, float base) {
        if (box_extra_base != base) { for (auto& i : info) i.box_extra = -1; box_extra_base = base; }
        auto& flag = info[style].box_extra;
        if (flag < 0) {
            const auto& props = computed[style];
            const float font = styles[style].font_size_px;
            flag = 0;
            for (const auto& p : props) {
                if(p.second.empty() || p.second=="none" || p.second=="transparent" || p.second=="auto" ||
                    p.second=="baseline")continue;
                const bool box=p.first.starts_with("background") || p.first=="border" ||
                    p.first=="border-width" || p.first=="border-radius" || p.first.starts_with("padding") ||
                    p.first.starts_with("margin") || p.first=="vertical-align";
                if(!box)continue;
                // Universal zero spacing resets must not split every otherwise mergeable text run.
                const auto numeric=CssNumeric(p.second,font,base>0?base:16.f,1).evaluate(false);
                if(!numeric || *numeric!=0) { flag = 1; break; }
            }
        }
        return flag != 0;
    }
    uint64_t elements = 0, unresolved = 0, unsupported = 0;
    size_t source_count = 0;
    float base_font_size_px = 16;
    uint32_t save(std::string_view s) { auto at = checked_index(text.size()); text.append(s); return at; }
    uint32_t intern(Properties properties, float size, float computed_size) {
        std::string key=std::to_string(size)+";";
        key.reserve(1024);
        char digits[24];
        auto put=[&](size_t n) { auto r=std::to_chars(digits,digits+sizeof digits,n); key.append(digits,r.ptr); };
        for (const auto& [name,value] : properties) {
            put(name.size()); key.push_back(':'); key.append(name);
            put(value.size()); key.push_back(':'); key.append(value);
        }
        if (auto found = interned.find(key); found != interned.end()) return found->second;
        const auto index = checked_index(styles.size());
        const auto first = checked_index(this->properties.size());
        for (const auto& [name,value] : properties) {
            if(name.starts_with("--")) continue;
            this->properties.push_back({save(name),checked_index(name.size()),save(value),checked_index(value.size())});
        }
        styles.push_back({first,checked_index(this->properties.size())-first,size});
        font_sizes.push_back(computed_size);
        computed.push_back(std::move(properties));
        info.push_back({property(computed.back(),"display"),property(computed.back(),"white-space"),property(computed.back(),"list-style-type"),-1});
        interned.emplace(std::move(key), index);
        return index;
    }
};

namespace {
struct Element {
    lxb_dom_element_t* dom;
    uint32_t parent, token, style = 0;
    std::string tag;
    bool hidden = false;
    bool disclosure_hidden = false;
    std::string marker;
    bool marker_used = false;
    uint32_t image_container = none;
    bool sizing_only = false;
    float computed_font_size = 16;
    uint32_t before = none, after = none;
};
struct SourceCompiler {
    const Storage& input;
    dc_prepared_document& output;
    uint32_t source;
    dc_compile_options options;
    std::vector<Element> elements;
    std::unordered_map<const lxb_css_rule_declaration_t*, std::pair<std::string,std::string>> declaration_cache;
    std::vector<uint32_t> operation_elements;
    uint32_t paragraph = none, cell = none, row = none, table = none, ruby = none;
    uint32_t block_style = 0;
    uint32_t marker_owner = none;
    bool pending_space = false;
    bool merge_barrier = false;
    bool structured_line_breaks = false;

    void flush() { paragraph = none; pending_space = false; }
    void add_text(std::string_view text, uint32_t style, uint32_t role, bool forced_break=false) {
        // appendTextWithLineBreaks() in the shipping JSON renderer turns literal
        // newlines into BRs, independently of CSS white-space collapsing.
        if(structured_line_breaks && !forced_break &&
            (text.find_first_of("\r\n")!=text.npos || text.find("\\n")!=text.npos)) {
            size_t first=0;
            while(first<text.size()) {
                const auto end=std::min(text.find_first_of("\r\n",first),text.find("\\n",first));
                if(end==text.npos){add_text(text.substr(first),style,role);break;}
                add_text(text.substr(first,end-first),style,role);
                pending_space=false;add_text("\n",style,role,true);pending_space=false;
                first=end+((text[end]=='\\' || (text[end]=='\r' && end+1<text.size() && text[end+1]=='\n'))?2:1);
            }
            return;
        }
        const auto whitespace = output.info[style].white_space;
        const bool preserve = whitespace == "pre" || whitespace == "pre-wrap" || whitespace == "break-spaces";
        std::string normalized;
        if (forced_break || preserve) normalized = text;
        else {
            bool previous_cr=false;
            for (char c : text) {
                if(whitespace=="pre-line" && (c=='\n' || c=='\r')) {
                    if(c!='\n' || !previous_cr)normalized+='\n';
                    previous_cr=c=='\r';pending_space=false;continue;
                }
                previous_cr=false;
                if (space(c)) { pending_space = true; continue; }
                if (pending_space && (!normalized.empty() || (paragraph != none && output.paragraphs[paragraph].run_count))) normalized += ' ';
                pending_space = false; normalized += c;
            }
        }
        if (normalized.empty()) return;
        if (paragraph == none) {
            paragraph = checked_index(output.paragraphs.size());
            uint32_t marker_offset=0,marker_bytes=0,marker_style=block_style;
            if(marker_owner!=none) {
                auto& owner=elements[marker_owner];
                if(!owner.marker_used) {
                    marker_offset=output.save(owner.marker);marker_bytes=checked_index(owner.marker.size());marker_style=owner.style;owner.marker_used=true;
                }
            }
            output.paragraphs.push_back({checked_index(output.runs.size()),0,source,cell,block_style,marker_offset,marker_bytes,marker_style});
            output.flow.push_back({0,paragraph,source,cell});
        }
        const auto offset = output.save(normalized);
        auto& p = output.paragraphs[paragraph];
        if (!merge_barrier && p.run_count && !output.runs.empty()) {
            auto& previous = output.runs.back();
            if (role != 3 && previous.paragraph == paragraph && previous.style == style && previous.role == role &&
                previous.ruby == ruby && previous.text_offset + previous.text_bytes == offset) {
                previous.text_bytes += checked_index(normalized.size()); return;
            }
        }
        output.runs.push_back({offset,checked_index(normalized.size()),style,role,ruby,paragraph});
        merge_barrier = false;
        ++p.run_count;
    }
    void resolve_styles() {
        std::string signature;
        char digits[24];
        for (auto& node : elements) {
            DeclarationCollector collector{{},declaration_cache};
            if (node.dom->style)
                require_status(lxb_dom_element_style_walk(node.dom, declaration, &collector, false), "CSS cascade extraction failed");
            const auto parent_style = node.parent == none ? none : elements[node.parent].style;
            signature.clear();
            signature.reserve(256);
            auto put=[&](size_t n) { auto r=std::to_chars(digits,digits+sizeof digits,n); signature.append(digits,r.ptr); };
            put(parent_style); signature.push_back(':'); signature.append(node.tag);
            signature += node.sizing_only ? ":sizing;" : ";";
            for (const auto& [name,decl] : collector.declarations)
            {
                put(name.size()); signature.push_back(':'); signature.append(name);
                put(decl.value.size()); signature.push_back(':'); signature.append(decl.value);
                if(name.starts_with("margin") || name.starts_with("padding")) {
                    signature.push_back('@'); put(decl.specificity); signature.push_back(':'); put(decl.offset);
                }
            }
            if (auto found = output.resolved_styles.find(signature); found != output.resolved_styles.end()) {
                node.style = found->second;
                node.computed_font_size = output.font_sizes[node.style];
                if(node.parent==none)output.base_font_size_px=node.computed_font_size;
                node.hidden = node.disclosure_hidden || (node.parent != none && elements[node.parent].hidden) || output.info[node.style].display == "none";
                ++output.elements;
                continue;
            }
            Properties computed;
            float parent_size = 16.f;
            if (node.parent != none) {
                const auto& parent = elements[node.parent];
                parent_size = parent.computed_font_size;
                for (const auto& [name,value] : output.computed[parent.style]) if (inherited(name)) computed[name] = value;
                node.hidden = node.disclosure_hidden || parent.hidden;
            }
            for (const auto& [name,decl] : collector.declarations) {
                const auto value = decl.value;
                if (value == "inherit" || (value == "unset" && inherited(name))) {
                    if(node.parent != none) {
                        const auto& parent = output.computed[elements[node.parent].style];
                        if(auto found = parent.find(name); found != parent.end()) computed[std::string(name)] = found->second;
                    }
                    continue;
                }
                if(value == "initial" || value == "unset") { if(auto found=computed.find(name);found!=computed.end())computed.erase(found); continue; }
                computed[std::string(name)] = value;
            }
            if(!collector.declarations.contains("font-weight") && (node.tag=="b" || node.tag=="strong" || node.tag=="th"))computed["font-weight"]="bold";
            if(!collector.declarations.contains("font-style") && (node.tag=="i" || node.tag=="em"))computed["font-style"]="italic";
            if(!collector.declarations.contains("text-decoration") && node.tag=="u")computed["text-decoration"]="underline";
            if(!computed.contains("display"))computed["display"]=default_display(node.tag);
            if(!collector.declarations.contains("list-style-type") && (node.tag=="ul" || node.tag=="ol"))computed["list-style-type"]=node.tag=="ol"?"decimal":"disc";
            if(node.tag=="summary" && !collector.declarations.contains("list-style-position"))computed["list-style-position"]="inside";
            for (auto& [name,value] : computed) if (!name.starts_with("--") && value.find("var(") != value.npos)
                value = resolve_vars(value, computed, 0, output.unresolved);
            expand_spacing(computed,collector);
            expand_borders(computed,collector);
            // Relative font sizes resolve only on the element that specifies them.
            float size = parent_size;
            if (collector.declarations.contains("font-size")) size = font_size(property(computed,"font-size"),parent_size,output.base_font_size_px,output.unresolved);
            else if(node.tag=="rt")size=parent_size*.5f;
            else if(node.tag=="small")size=parent_size*.8f;
            else if(node.tag=="sup" || node.tag=="sub")size=parent_size*.75f;
            // Minimum font size affects painted text, not inherited computed em
            // sizes: a 0.5em wrapper followed by 2em must restore the parent size.
            node.computed_font_size=size;
            if(node.parent==none)output.base_font_size_px=size;
            const float used_size=size>0 && !node.sizing_only?std::max(size,options.minimum_font_size_px):size;
            if(node.tag=="rt" && !collector.declarations.contains("line-height"))computed["line-height"]="normal";
            computed["font-size"] = std::to_string(size) + "px";
            node.hidden = node.hidden || property(computed,"display") == "none";
            node.style = output.intern(std::move(computed), used_size, size);
            output.resolved_styles.emplace(std::move(signature), node.style);
            ++output.elements;
        }
    }
    void compile(const SourceRange& range, const std::vector<RuleCandidate>& candidates,
        const std::vector<GeneratedRule>& generated_rules, bool verify_candidates) {
        structured_line_breaks=range.json;
        Dom doc;
        auto* body = lxb_dom_interface_element(lxb_html_document_body_element(doc.value));
        attribute(body,"id","entries");
        auto* root = element(doc, "span");
        attribute(root, "class", "structured-content");
        if(!range.json)attribute(root,"style","white-space:pre-wrap");
        append(lxb_dom_interface_node(body), lxb_dom_interface_node(root));
        auto* html = lxb_dom_document_element(lxb_dom_interface_document(doc.value));
        if(options.dark_mode) attribute(html,"data-theme","dark");
        attribute(html,"style","--font-size-no-units:"+std::to_string(options.font_size_px));
        elements.push_back({html,none,none,0,"html"});
        elements.push_back({body,0,none,0,"body"});
        elements.push_back({root,1,none,0,"span"});
        std::vector<uint32_t> stack{2};
        size_t first = 0;
        while (first < input.operations.size() && input.operations[first].token < range.first) ++first;
        size_t last = first;
        while (last < input.operations.size() && input.operations[last].token < range.end) ++last;
        operation_elements.reserve(last - first);
        for (size_t at = first; at < last; ++at) {
            const auto& op = input.operations[at];
            if (op.kind == OpKind::close || op.kind == OpKind::reading_close) {
                const bool table_close=elements[stack.back()].tag=="table";
                operation_elements.push_back(stack.back()); stack.pop_back();
                if(table_close)stack.pop_back();
                continue;
            }
            if (op.kind == OpKind::text) {
                const auto text = input.value(op.token);
                auto* node = lxb_dom_document_create_text_node(lxb_dom_interface_document(doc.value), bytes(text), text.size());
                if (!node) throw std::bad_alloc();
                append(lxb_dom_interface_node(elements[stack.back()].dom), lxb_dom_interface_node(node));
                operation_elements.push_back(stack.back()); continue;
            }
            std::string tag = op.kind == OpKind::reading_open ? "rt" : scalar(input,find(input,op.token,"tag"));
            if (op.kind == OpKind::open && find(input, op.token, "ruby") != none) tag = "ruby";
            if (tag.empty()) tag = "span";
            if (scalar(input,find(input,op.token,"type")) == "image") tag = "img";
            if(tag=="img") {
                // Mirror the shipping image hierarchy so descendant CSS sizes the
                // container rather than an invented direct image box.
                auto synthetic=[&](std::string_view tag_name,std::string_view classes,uint32_t parent) {
                    auto* dom=element(doc,tag_name);attribute(dom,"class",classes);
                    append(lxb_dom_interface_node(elements[parent].dom),lxb_dom_interface_node(dom));
                    const auto id=checked_index(elements.size());
                    size_t open_size=0;
                    const bool closed=elements[parent].tag=="details" &&
                        !lxb_dom_element_get_attribute(elements[parent].dom,bytes("open"),4,&open_size);
                    elements.push_back({dom,parent,op.token,0,std::string(tag_name),false,elements[parent].disclosure_hidden || closed});
                    return id;
                };
                const auto link=synthetic("span","gloss-image-link gloss-sc-a",stack.back());
                copy_attributes(input,op.token,elements[link].dom);
                const auto data=find(input,op.token,"data");
                auto field=[&](std::string_view name) {
                    auto value=find(input,op.token,name);
                    if(value==none && data!=none)value=find(input,data,name);
                    return scalar(input,value);
                };
                auto numeric=[&](std::string_view name,float fallback) {
                    const auto value=field(name);
                    return CssNumeric(value,1,16,0).evaluate().value_or(fallback);
                };
                const float natural_width=numeric("width",100),natural_height=numeric("height",100);
                const float preferred_width=numeric("preferredWidth",0),preferred_height=numeric("preferredHeight",0);
                const float aspect=std::max(.01f,(preferred_height>0?preferred_height:natural_height)/
                    std::max(1.f,preferred_width>0?preferred_width:natural_width));
                const float used_width=preferred_width>0?preferred_width:preferred_height>0?preferred_height/aspect:natural_width;
                const auto units=field("sizeUnits");
                if(!units.empty())attribute(elements[link].dom,"data-size-units",units);
                attribute(elements[link].dom,"data-has-aspect-ratio","true");
                const auto container=synthetic("span","gloss-image-container",link);
                elements[container].sizing_only=true;
                attribute(elements[container].dom,"style","width:"+std::to_string(used_width)+"em;max-width:100%");
                const auto sizer=synthetic("span","gloss-image-sizer",container);
                elements[sizer].sizing_only=true;
                attribute(elements[sizer].dom,"style","padding-top:"+std::to_string(aspect*100)+"%");
                synthetic("span","gloss-image-background",container);
                const auto image=synthetic("img","gloss-image gloss-sc-img",container);
                elements[image].image_container=container;
                stack.push_back(image);operation_elements.push_back(image);continue;
            }
            // Shipping structured links are spans, not anchors. Selectors such as
            // :has(a) must see the same element names as the WebView renderer.
            const bool link=tag=="a";
            if(tag=="table") {
                auto* wrapper=element(doc,"div");
                attribute(wrapper,"class","gloss-sc-table-container");
                append(lxb_dom_interface_node(elements[stack.back()].dom),lxb_dom_interface_node(wrapper));
                const auto wrapper_id=checked_index(elements.size());
                elements.push_back({wrapper,stack.back(),op.token,0,"div",false,elements[stack.back()].disclosure_hidden});
                stack.push_back(wrapper_id);
            }
            auto* node = element(doc, link?"span":tag);
            attribute(node, "class", link?"gloss-link gloss-sc-a":"gloss-sc-" + tag);
            copy_attributes(input,op.token,node);
            if(tag=="details" && options.expand_details)attribute(node,"open","");
            append(lxb_dom_interface_node(elements[stack.back()].dom),lxb_dom_interface_node(node));
            const auto id = checked_index(elements.size());
            const auto& parent = elements[stack.back()];
            size_t attribute_length = 0;
            const bool closed = parent.tag == "details" && !lxb_dom_element_get_attribute(parent.dom,bytes("open"),4,&attribute_length);
            const bool disclosure_hidden = parent.disclosure_hidden || (closed && tag != "summary");
            elements.push_back({node,stack.back(),op.token,0,tag,false,disclosure_hidden}); stack.push_back(id); operation_elements.push_back(id);
        }
        std::unordered_set<std::string_view> present;
        for(const auto& node:elements) {
            size_t size=0;const auto* tag=lxb_dom_element_local_name(node.dom,&size);
            present.emplace(reinterpret_cast<const char*>(tag),size);
            for(auto* attr=lxb_dom_element_first_attribute(node.dom);attr;attr=lxb_dom_element_next_attribute(attr)) {
                const auto* name=lxb_dom_attr_local_name(attr,&size);
                present.emplace(reinterpret_cast<const char*>(name),size);
            }
            const auto* classes=lxb_dom_element_get_attribute(node.dom,bytes("class"),5,&size);
            if(classes) {
                const std::string_view value(reinterpret_cast<const char*>(classes),size);
                size_t at=0;
                while(at<value.size()) {
                    while(at<value.size() && space(value[at]))++at;
                    const auto first=at;while(at<value.size() && !space(value[at]))++at;
                    if(at>first)present.emplace(value.substr(first,at-first));
                }
            }
            const auto* id=lxb_dom_element_get_attribute(node.dom,bytes("id"),2,&size);
            if(id)present.emplace(reinterpret_cast<const char*>(id),size);
        }
        for(const auto& candidate:candidates) {
            bool possible=false;
            for(const auto& required:candidate.alternatives) {
                if(std::all_of(required.begin(),required.end(),[&](const auto& key){return present.contains(std::string_view(key).substr(2));})) {possible=true;break;}
            }
            if(possible) {
                require_status(lxb_dom_document_style_attach(lxb_dom_interface_document(doc.value),candidate.rule),"CSS matching failed");
            } else if(verify_candidates) {
                bool matched=false;
                auto callback=[](lxb_dom_node_t*,lxb_css_selector_specificity_t,void* value)->lxb_status_t{*static_cast<bool*>(value)=true;return LXB_STATUS_OK;};
                require_status(lxb_selectors_find(lxb_dom_interface_document(doc.value)->css->selectors,lxb_dom_interface_node(doc.value),candidate.rule->selector,callback,&matched),"CSS candidate verification failed");
                if(matched)throw std::runtime_error("CSS candidate filter rejected a matching rule");
            }
        }
        // Match pseudo-elements against their originating elements without adding
        // DOM children (which would change :empty and structural selectors).
        std::unordered_map<lxb_dom_element_t*,uint32_t> element_ids;
        if(!generated_rules.empty())for(uint32_t i=0;i<elements.size();++i)element_ids.emplace(elements[i].dom,i);
        for(const auto& entry:generated_rules) {
            const auto& candidate=candidates[entry.candidate];
            auto* list=entry.list;
            const auto* pseudo=list->last;
            const std::string_view pseudo_name(reinterpret_cast<const char*>(pseudo->name.data),pseudo->name.length);
            std::vector<lxb_css_selector_t> selectors;
            for(auto* selector=list->first;selector!=pseudo;selector=selector->next)selectors.push_back(*selector);
            auto owner_list=*list;owner_list.next=owner_list.prev=nullptr;
            if(selectors.empty()) {
                selectors.push_back({});selectors.back().type=LXB_CSS_SELECTOR_TYPE_ANY;
            }
            for(size_t i=0;i<selectors.size();++i) {
                selectors[i].prev=i?&selectors[i-1]:nullptr;
                selectors[i].next=i+1<selectors.size()?&selectors[i+1]:nullptr;
                selectors[i].list=&owner_list;
            }
            owner_list.first=&selectors.front();owner_list.last=&selectors.back();
            std::vector<lxb_dom_element_t*> matched;
            auto callback=[](lxb_dom_node_t* node,lxb_css_selector_specificity_t,void* context)->lxb_status_t {
                try {static_cast<std::vector<lxb_dom_element_t*>*>(context)->push_back(lxb_dom_interface_element(node));return LXB_STATUS_OK;}
                catch(...){return LXB_STATUS_ERROR_MEMORY_ALLOCATION;}
            };
            require_status(lxb_selectors_find(lxb_dom_interface_document(doc.value)->css->selectors,
                lxb_dom_interface_node(doc.value),&owner_list,callback,&matched),"Generated content matching failed");
            for(auto* dom:matched) {
                const auto owner=element_ids.at(dom);
                const bool before=pseudo_name=="data-dc-generated-before";
                auto id=before?elements[owner].before:elements[owner].after;
                if(id==none) {
                    id=checked_index(elements.size());
                    elements.push_back({element(doc,"span"),owner,none,0,"span"});
                    if(before)elements[owner].before=id;else elements[owner].after=id;
                }
                auto specificity=list->specificity;
                lxb_css_selector_sp_set_b(specificity,lxb_css_selector_sp_b(specificity)-1);
                lxb_css_selector_sp_add_c(specificity,1);
                require_status(lxb_dom_element_style_list_append(elements[id].dom,candidate.rule->declarations,specificity),
                    "Generated content cascade failed");
            }
        }
        resolve_styles();
        auto generated=[&](uint32_t id) {
            if(id==none || elements[id].hidden)return;
            const auto& node=elements[id];const auto content=property(output.computed[node.style],"content");
            if(content.empty() || content=="none" || content=="normal")return;
            auto* raw=lxb_css_syntax_tokenizer_create();if(!raw)throw std::bad_alloc();
            std::unique_ptr<lxb_css_syntax_tokenizer_t,decltype(&lxb_css_syntax_tokenizer_destroy)> tokens(raw,lxb_css_syntax_tokenizer_destroy);
            css_require(lxb_css_syntax_tokenizer_init(raw));
            lxb_css_syntax_tokenizer_buffer_set(raw,bytes(content),content.size());
            std::string text;
            for(;;) {
                auto* token=lxb_css_syntax_token(raw);if(!token)throw std::runtime_error("Generated content tokenization failed");
                if(token->type==LXB_CSS_SYNTAX_TOKEN__EOF)break;
                if(token->type==LXB_CSS_SYNTAX_TOKEN_STRING)text.append(reinterpret_cast<const char*>(token->types.string.data),token->types.string.length);
                else if(token->type!=LXB_CSS_SYNTAX_TOKEN_WHITESPACE){++output.unsupported;return;}
                lxb_css_syntax_token_consume(raw);
            }
            add_text(text,node.style,ruby!=none?1:0);
        };
        std::vector<bool> block_descendants(elements.size(),false);
        for(size_t i=elements.size();i-->0;) {
            if(elements[i].hidden || elements[i].parent==none)continue;
            const auto display=output.info[elements[i].style].display;
            const bool block=display!="inline" && display!="inline-block" && display!="inline-flex" &&
                display!="flex" && display!="ruby" && display!="ruby-text" && display!="contents";
            if(block || block_descendants[i])block_descendants[elements[i].parent]=true;
        }
        block_style = elements[2].style;
        output.flow.push_back({2,block_style,source,none});
        struct Owners { uint32_t cell,row,table,ruby,block_style,marker_owner,first_run; bool block,inline_box; };
        std::vector<Owners> owners;
        std::unordered_map<uint32_t,uint32_t> list_counters;
        uint32_t reading_start = none;
        for (size_t at = first; at < last; ++at) {
            const auto& op = input.operations[at];
            auto& node = elements[operation_elements[at-first]];
            if (node.hidden) continue;
            if (op.kind == OpKind::text) {
                size_t open_size=0;
                if(node.tag=="details" && !lxb_dom_element_get_attribute(node.dom,bytes("open"),4,&open_size)) continue;
                add_text(input.value(op.token),node.style,node.tag == "rt" || reading_start != none ? 2 : ruby != none ? 1 : 0);
                continue;
            }
            const auto display = output.info[node.style].display;
            bool inline_flex=false;
            if(display=="flex") {
                unsigned items=0;bool text_item=false;
                for(auto* child=lxb_dom_interface_node(node.dom)->first_child;child;child=child->next) {
                    if(child->type==LXB_DOM_NODE_TYPE_ELEMENT){++items;text_item=false;}
                    else if(child->type==LXB_DOM_NODE_TYPE_TEXT) {
                        const auto& data=lxb_dom_interface_character_data(child)->data;
                        const std::string_view text(reinterpret_cast<const char*>(data.data),data.length);
                        if(!trim(text).empty() && !text_item){++items;text_item=true;}
                    }
                }
                if(items==1)for(auto ancestor=node.parent;ancestor!=none;ancestor=elements[ancestor].parent) {
                    const auto ancestor_display=output.info[elements[ancestor].style].display;
                    if(ancestor_display=="inline-block" || ancestor_display=="inline-flex"){inline_flex=true;break;}
                    if(ancestor_display!="inline" && ancestor_display!="ruby" && ancestor_display!="ruby-text")break;
                }
            }
            const bool container=display=="inline-block" && block_descendants[operation_elements[at-first]];
            const bool block = !inline_flex && (container || (display != "inline" && display != "inline-block" && display != "inline-flex" && display != "ruby" && display != "ruby-text"));
            if (op.kind == OpKind::open || op.kind == OpKind::reading_open) {
                if (display == "flex" || display == "grid") ++output.unsupported;
                const bool inline_box=!block && (display=="inline" || display=="inline-block" || display=="inline-flex" || inline_flex) &&
                    (display=="inline-block" || display=="inline-flex" || output.has_box_extra(node.style,output.base_font_size_px));
                owners.push_back({cell,row,table,ruby,block_style,marker_owner,checked_index(output.runs.size()),block,inline_box});
                if(inline_box)merge_barrier=true;
                if (block) flush();
                const bool structural = node.tag == "table" || node.tag == "tr" || node.tag == "td" || node.tag == "th" || node.tag == "tbody" || node.tag == "thead" || node.tag == "tfoot";
                if (block && !structural) output.flow.push_back({2,node.style,source,cell});
                if (block) block_style = node.style;
                if(display=="list-item") {
                    const auto type=output.info[node.style].list_style_type;
                    if(type!="none") {
                        if(node.tag=="summary") {
                            size_t open_size=0;
                            const bool open=node.parent!=none && lxb_dom_element_get_attribute(elements[node.parent].dom,bytes("open"),4,&open_size);
                            node.marker=open?"\xe2\x96\xbe ":"\xe2\x96\xb8 ";
                        } else if(type=="decimal")node.marker=std::to_string(++list_counters[node.parent])+".";
                        else if(type=="circle")node.marker="\xe2\x97\xa6";
                        else if(type=="square")node.marker="\xe2\x96\xaa";
                        else if(type.size()>=2 && (type.front()=='\"' || type.front()=='\'') && type.back()==type.front())
                            node.marker=type.substr(1,type.size()-2);
                        else if(type.empty() || type=="disc")node.marker="\xe2\x80\xa2";
                        else ++output.unsupported;
                    }
                    if(!node.marker.empty())marker_owner=operation_elements[at-first];
                    else if(marker_owner!=none && elements[marker_owner].marker_used)marker_owner=none;
                }
                if (node.tag == "table") {
                    output.flow.push_back({2,elements[node.parent].style,source,cell});
                    table = checked_index(output.tables.size());
                    output.tables.push_back({checked_index(output.rows.size()),0,source,cell,node.style});
                    output.flow.push_back({1,table,source,cell});
                }
                else if (node.tag == "tr" && table != none) { row = checked_index(output.rows.size()); output.rows.push_back({checked_index(output.cells.size()),0,table}); ++output.tables[table].row_count; }
                else if ((node.tag == "td" || node.tag == "th") && row != none) {
                    cell = checked_index(output.cells.size());
                    output.cells.push_back({checked_index(output.paragraphs.size()),0,row,positive(input,op.token,"colSpan"),positive(input,op.token,"rowSpan"),node.style});
                    ++output.rows[row].cell_count;
                } else if (node.tag == "ruby") {
                    ruby = checked_index(output.rubies.size()); output.rubies.push_back({checked_index(output.runs.size()),0,0,0});
                } else if (node.tag == "rt" && ruby != none) {
                    auto& r = output.rubies[ruby];
                    r.base_run_count = checked_index(output.runs.size()) - r.first_base_run;
                    r.first_reading_run = checked_index(output.runs.size()); reading_start = r.first_reading_run;
                } else if (node.tag == "br") add_text("\n",node.style,0,true);
                else if (node.tag == "img") {
                    add_text("\xef\xbf\xbc",node.style,3);
                    const auto first_property=checked_index(output.properties.size());
                    auto copy=[&](uint32_t object) {
                        if(input.tokens[object].kind!=Kind::object)return;
                        for(auto at=object+1;at<input.tokens[object].end;) {
                            const auto name=input.value(at++);const auto value=scalar(input,at);
                            if(input.tokens[at].kind==Kind::string || input.tokens[at].kind==Kind::number || input.tokens[at].kind==Kind::boolean)
                                output.properties.push_back({output.save(name),checked_index(name.size()),output.save(value),checked_index(value.size())});
                            at=input.tokens[at].end;
                        }
                    };
                    const auto data=find(input,op.token,"data");if(data!=none)copy(data);copy(op.token);
                    if(node.image_container!=none) {
                        const std::string name="container-style",value=std::to_string(elements[node.image_container].style);
                        output.properties.push_back({output.save(name),checked_index(name.size()),output.save(value),checked_index(value.size())});
                    }
                    output.images.push_back({checked_index(output.runs.size()-1),first_property,checked_index(output.properties.size())-first_property});
                }
                generated(node.before);
            } else {
                generated(node.after);
                if (node.tag == "rt" && ruby != none) { output.rubies[ruby].reading_run_count = checked_index(output.runs.size()) - reading_start; reading_start = none; }
                if (node.tag == "td" || node.tag == "th") {
                    if (cell != none) output.cells[cell].paragraph_count = checked_index(output.paragraphs.size()) - output.cells[cell].first_paragraph;
                }
                if (block) flush();
                const bool structural = node.tag == "table" || node.tag == "tr" || node.tag == "td" || node.tag == "th" || node.tag == "tbody" || node.tag == "thead" || node.tag == "tfoot";
                if (block && !structural) output.flow.push_back({3,node.style,source,cell});
                const auto restore = owners.back(); owners.pop_back();
                if(node.tag=="table")output.flow.push_back({3,elements[node.parent].style,source,restore.cell});
                if(restore.inline_box) {
                    const auto count=checked_index(output.runs.size())-restore.first_run;
                    if(count)output.inline_boxes.push_back({restore.first_run,count,node.style});
                    merge_barrier=true;
                }
                cell=restore.cell; row=restore.row; table=restore.table; ruby=restore.ruby;
                block_style=restore.block_style;
                marker_owner=restore.marker_owner;
            }
        }
        flush();
        output.flow.push_back({3,elements[2].style,source,none});
    }
};
void failure(dc_error* error,const std::exception& e) {
    if(error) std::snprintf(error->message,sizeof(error->message),"%s",e.what());
}
}

int dc_stylesheets_create(const dc_css_source* sources,size_t count,dc_stylesheets** output,dc_error* error) {
    if(output) *output=nullptr;
    if(error) *error={};
    try {
        if(!output || (!sources && count)) throw std::invalid_argument("Invalid stylesheet arguments");
        const auto key=stylesheet_key(sources,count);
        {
            std::lock_guard<std::mutex> lock(sheet_cache().mutex);
            for(auto& entry:sheet_cache().entries)if(entry.first==key) {
                ++entry.second->refs;
                *output=entry.second;
                return 0;
            }
        }
        auto result=std::make_unique<dc_stylesheets>();
        result->parser=lxb_css_parser_create();
        if(!result->parser) throw std::bad_alloc();
        require_status(lxb_css_parser_init(result->parser,nullptr),"CSS parser initialization failed");
        for(size_t i=0;i<count;++i) {
            auto* sheet=lxb_css_stylesheet_create(nullptr);
            if(!sheet) throw std::bad_alloc();
            result->sheets.push_back(sheet);
            require_status(lxb_css_stylesheet_parse(sheet,result->parser,reinterpret_cast<const lxb_char_t*>(sources[i].utf8),sources[i].bytes),"CSS parse failed");
            if(dictionary_core::has_nested_rules(lxb_css_rule_list(sheet->root))) {
                std::string flattened;
                dictionary_core::flatten_css(lxb_css_rule_list(sheet->root),{},flattened);
                auto* normalized=lxb_css_stylesheet_create(nullptr);
                if(!normalized)throw std::bad_alloc();
                result->sheets.push_back(normalized);
                require_status(lxb_css_stylesheet_parse(normalized,result->parser,bytes(flattened),flattened.size()),"Flattened CSS parse failed");
                result->sheets.pop_back();
                lxb_css_stylesheet_destroy(sheet,true);sheet=normalized;result->sheets.back()=sheet;
            }
            uint64_t unsupported = 0;
            std::vector<RuleCandidate> candidates;
            std::vector<GeneratedRule> generated;
            for (auto* rule = lxb_css_rule_list(sheet->root)->first; rule; rule = rule->next) {
                if (rule->type != LXB_CSS_RULE_STYLE) { ++unsupported; continue; }
                RuleCandidate candidate{lxb_css_rule_style(rule),{}};
                if(candidate.rule->declarations) for(auto* list=candidate.rule->selector;list;list=list->next) {
                    const auto* pseudo=list->last;
                    if(!pseudo || pseudo->type!=LXB_CSS_SELECTOR_TYPE_ATTRIBUTE)continue;
                    const std::string_view name(reinterpret_cast<const char*>(pseudo->name.data),pseudo->name.length);
                    if(name=="data-dc-generated-before" || name=="data-dc-generated-after")generated.push_back({candidates.size(),list});
                }
                for(auto* list=candidate.rule->selector;list;list=list->next) {
                    std::vector<std::string> required;
                    for(auto* selector=list->first;selector;selector=selector->next) {
                        if(selector->ns.length)continue;
                        const auto type=selector->type;
                        std::string prefix;
                        if(type==LXB_CSS_SELECTOR_TYPE_ELEMENT)prefix="t:";
                        else if(type==LXB_CSS_SELECTOR_TYPE_ATTRIBUTE)prefix="a:";
                        else if(type==LXB_CSS_SELECTOR_TYPE_CLASS)prefix="c:";
                        else if(type==LXB_CSS_SELECTOR_TYPE_ID)prefix="i:";
                        else continue;
                        std::string name(reinterpret_cast<const char*>(selector->name.data),selector->name.length);
                        if(type==LXB_CSS_SELECTOR_TYPE_ELEMENT || type==LXB_CSS_SELECTOR_TYPE_ATTRIBUTE)
                            for(auto& c:name)if(c>='A' && c<='Z')c=static_cast<char>(c+32);
                        required.push_back(prefix+name);
                    }
                    candidate.alternatives.push_back(std::move(required));
                }
                candidates.push_back(std::move(candidate));
            }
            result->candidates.push_back(std::move(candidates));
            result->generated.push_back(std::move(generated));
            result->unsupported_rules.push_back(unsupported);
        }
        {
            std::lock_guard<std::mutex> lock(sheet_cache().mutex);
            for(auto& entry:sheet_cache().entries)if(entry.first==key) {
                ++entry.second->refs;
                release_sheets(result.release());
                *output=entry.second;
                return 0;
            }
            if(sheet_cache().entries.size()>=4) {
                release_sheets(sheet_cache().entries.front().second);
                sheet_cache().entries.erase(sheet_cache().entries.begin());
            }
            auto* created=result.release();
            ++created->refs;
            sheet_cache().entries.emplace_back(key,created);
            *output=created;
            return 0;
        }
    } catch(const std::exception& e) { failure(error,e);return 1; }
    catch(...) { if(error) std::snprintf(error->message,sizeof(error->message),"Unknown native CSS failure"); return 1; }
}
void dc_stylesheets_destroy(dc_stylesheets* sheets) { release_sheets(sheets); }
void dc_stylesheets_verify_candidates(dc_stylesheets* sheets,int enabled) { if(sheets)sheets->verify_candidates=enabled!=0; }
int dc_compile_styled(const dc_document* input,dc_stylesheets* sheets,const uint32_t* indices,size_t count,dc_prepared_document** output,dc_error* error) {
    const dc_compile_options options{16.f,0,0,8.f};
    return dc_compile_styled_configured(input,sheets,indices,count,&options,output,error);
}
int dc_compile_styled_configured(const dc_document* input,dc_stylesheets* sheets,const uint32_t* indices,size_t count,const dc_compile_options* options,dc_prepared_document** output,dc_error* error) {
    if(output) *output=nullptr;
    if(error) *error={};
    try {
        if(!input || !sheets || !output || (!indices && count) || count != input->storage.sources.size()) throw std::invalid_argument("Invalid styled batch arguments");
        if(!options || !std::isfinite(options->font_size_px) || options->font_size_px<=0) throw std::invalid_argument("Invalid font size");
        if(!std::isfinite(options->minimum_font_size_px) || options->minimum_font_size_px<0)throw std::invalid_argument("Invalid minimum font size");
        auto result=std::make_unique<dc_prepared_document>();
        result->source_count=count;
        for(size_t i=0;i<count;++i) {
            if(error) error->source=static_cast<uint32_t>(i);
            if(indices[i]>=sheets->sheets.size()) throw std::out_of_range("Invalid stylesheet index");
            result->unsupported += sheets->unsupported_rules[indices[i]];
            SourceCompiler compiler{input->storage,*result,static_cast<uint32_t>(i),*options};
            compiler.compile(input->storage.sources[i],sheets->candidates[indices[i]],sheets->generated[indices[i]],sheets->verify_candidates);
        }
        result->resolved=std::make_unique<dictionary_core::ResolvedStyles>(dc_prepared_document_view(result.get()));
        *output=result.release();return 0;
    } catch(const std::exception& e) { failure(error,e);return 1; }
    catch(...) { if(error) std::snprintf(error->message,sizeof(error->message),"Unknown native lowering failure"); return 1; }
}
dc_prepared_view dc_prepared_document_view(const dc_prepared_document* d) {
    if(!d) return {};
    return {d->text.data(),d->text.size(),d->runs.data(),d->runs.size(),d->paragraphs.data(),d->paragraphs.size(),
        d->rubies.data(),d->rubies.size(),d->tables.data(),d->tables.size(),d->rows.data(),d->rows.size(),d->cells.data(),d->cells.size(),
        d->styles.data(),d->styles.size(),d->properties.data(),d->properties.size(),d->flow.data(),d->flow.size(),d->images.data(),d->images.size(),d->source_count,d->elements,d->unresolved,d->unsupported,d->base_font_size_px,d->inline_boxes.data(),d->inline_boxes.size()};
}
uint64_t dc_prepared_document_digest(const dc_prepared_document* d) {
    if(!d) return 0;
    uint64_t hash=14695981039346656037ull;
    auto add=[&](const void* value,size_t bytes) { auto* p=static_cast<const uint8_t*>(value);for(size_t i=0;i<bytes;++i){hash^=p[i];hash*=1099511628211ull;} };
    add(d->text.data(),d->text.size());
    add(d->runs.data(),d->runs.size()*sizeof(dc_text_run));
    add(d->paragraphs.data(),d->paragraphs.size()*sizeof(dc_paragraph_input));
    add(d->rubies.data(),d->rubies.size()*sizeof(dc_ruby_input));
    add(d->tables.data(),d->tables.size()*sizeof(dc_table_input));
    add(d->rows.data(),d->rows.size()*sizeof(dc_row_input));
    add(d->cells.data(),d->cells.size()*sizeof(dc_cell_input));
    add(d->styles.data(),d->styles.size()*sizeof(dc_style_input));
    add(d->properties.data(),d->properties.size()*sizeof(dc_style_property));
    add(d->flow.data(),d->flow.size()*sizeof(dc_flow_input));
    add(d->images.data(),d->images.size()*sizeof(dc_image_input));
    add(d->inline_boxes.data(),d->inline_boxes.size()*sizeof(dc_inline_box));
    return hash;
}
void dc_prepared_document_destroy(dc_prepared_document* document) { delete document; }
const dictionary_core::ResolvedStyles &dictionary_core::resolved_styles(const dc_prepared_document *document) {
    return *document->resolved;
}
