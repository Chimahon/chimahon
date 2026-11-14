#include "document_storage.hpp"
#include "profile.hpp"
#include <rapidjson/reader.h>
#include <rapidjson/memorystream.h>
#include <rapidjson/error/en.h>
#include <cstdio>
#include <memory>
#include <cstring>
#include <lexbor/html/html.h>
#include <lexbor/dom/interfaces/character_data.h>

namespace {
using namespace dictionary_core;
void html_node(Storage& storage,lxb_dom_node_t* node,size_t depth) {
    if(depth>512)throw std::length_error("HTML nesting exceeds 512");
    if(node->type==LXB_DOM_NODE_TYPE_TEXT) {
        const auto& data=lxb_dom_interface_character_data(node)->data;
        storage.scalar(Kind::string,{reinterpret_cast<const char*>(data.data),data.length});
        return;
    }
    if(node->type!=LXB_DOM_NODE_TYPE_ELEMENT)return;
    auto* element=lxb_dom_interface_element(node);size_t size=0;
    auto* local=lxb_dom_element_local_name(element,&size);
    const std::string_view tag(reinterpret_cast<const char*>(local),size);
    if(tag=="script" || tag=="style" || tag=="template")return;
    const auto object=storage.begin(Kind::object);
    storage.scalar(Kind::key,"tag",Field::tag);storage.scalar(Kind::string,tag);
    storage.scalar(Kind::key,"htmlAttributes");const auto attributes=storage.begin(Kind::object);
    for(auto* attr=lxb_dom_element_first_attribute(element);attr;attr=lxb_dom_element_next_attribute(attr)) {
        auto* name=lxb_dom_attr_local_name(attr,&size);
        const std::string key(reinterpret_cast<const char*>(name),size);
        auto* value=lxb_dom_attr_value(attr,&size);
        storage.scalar(Kind::key,key);
        storage.scalar(Kind::string,{value?reinterpret_cast<const char*>(value):"",size});
    }
    storage.end(attributes);
    for(const auto& name:{"colspan","rowspan"}) {
        auto* value=lxb_dom_element_get_attribute(element,reinterpret_cast<const lxb_char_t*>(name),7,&size);
        if(value) {
            storage.scalar(Kind::key,name==std::string_view("colspan")?"colSpan":"rowSpan");
            storage.scalar(Kind::string,{reinterpret_cast<const char*>(value),size});
        }
    }
    storage.scalar(Kind::key,"content",Field::content);const auto children=storage.begin(Kind::array);
    for(auto* child=node->first_child;child;child=child->next)html_node(storage,child,depth+1);
    storage.end(children);storage.end(object);
}
void html_source(Storage& storage,std::string_view input) {
    auto* document=lxb_html_document_create();
    if(!document)throw std::bad_alloc();
    std::unique_ptr<lxb_html_document_t,decltype(&lxb_html_document_destroy)> owner(document,lxb_html_document_destroy);
    if(lxb_html_document_parse(document,reinterpret_cast<const lxb_char_t*>(input.data()),input.size())!=LXB_STATUS_OK)
        throw std::runtime_error("HTML parsing failed");
    const auto array=storage.begin(Kind::array);
    auto* body=lxb_dom_interface_node(lxb_html_document_body_element(document));
    for(auto* node=body->first_child;node;node=node->next)html_node(storage,node,0);
    storage.end(array);
}
struct Handler : rapidjson::BaseReaderHandler<rapidjson::UTF8<>, Handler> {
    Storage& storage;
    std::vector<uint32_t> parents;
    explicit Handler(Storage& storage) : storage(storage) { parents.reserve(32); }
    bool StartObject() { return start(Kind::object); }
    bool StartArray() { return start(Kind::array); }
    bool start(Kind kind) {
        if (parents.size() >= 512) return false;
        parents.push_back(storage.begin(kind)); return true;
    }
    bool EndObject(rapidjson::SizeType) { return end(); }
    bool EndArray(rapidjson::SizeType) { return end(); }
    bool end() { storage.end(parents.back()); parents.pop_back(); return true; }
    bool Key(const char* s, rapidjson::SizeType n, bool) {
        const std::string_view name(s, n);
        storage.scalar(Kind::key, name, field_name(name)); return true;
    }
    bool String(const char* s, rapidjson::SizeType n, bool) { storage.scalar(Kind::string, {s,n}); return true; }
    bool Null() { storage.scalar(Kind::null_value, {}); return true; }
    bool Bool(bool n) { storage.scalar(Kind::boolean, n ? "true" : "false"); return true; }
    bool Int(int n) { storage.number(n); return true; }
    bool Uint(unsigned n) { storage.number(n); return true; }
    bool Int64(int64_t n) { storage.number(n); return true; }
    bool Uint64(uint64_t n) { storage.number(n); return true; }
    bool Double(double n) { storage.number(n); return true; }
};
void typed_source(Storage &storage, dc_typed_source input) {
    if (!input.data || input.bytes < 12) throw std::invalid_argument("Invalid card source");
    const auto *data = static_cast<const unsigned char *>(input.data);
    auto read = [&](size_t at) { uint32_t n; std::memcpy(&n, data + at, 4); return n; };
    const auto count = read(4), bytes = read(8);
    if (read(0) != 0x43415244 || static_cast<uint64_t>(count) * 16 + bytes + 12 != input.bytes)
        throw std::invalid_argument("Invalid card source size");
    const auto *strings = reinterpret_cast<const char *>(data + 12 + static_cast<size_t>(count) * 16);
    auto kind = [&](uint32_t i) { return read(12 + static_cast<size_t>(i) * 16); };
    auto value = [&](uint32_t i) -> std::string_view {
        const size_t at = 12 + static_cast<size_t>(i) * 16;
        const auto start = read(at + 8), length = read(at + 12);
        if (start > bytes || length > bytes - start)
            throw std::invalid_argument("Invalid card command");
        return {strings + start, length};
    };
    const auto string_base = checked_index(storage.strings.size());
    checked_index(static_cast<size_t>(string_base) + bytes);
    storage.strings.append(strings, bytes);
    auto scalar = [&](Kind token_kind, uint32_t i, Field field = Field::other) {
        const auto text = value(i);
        const auto token = checked_index(storage.tokens.size());
        storage.tokens.push_back({token_kind, field, 0, token + 1,
            string_base + static_cast<uint32_t>(text.data() - strings), checked_index(text.size())});
        return token;
    };
    const auto root = storage.begin(Kind::array);
    const auto first_operation = checked_index(storage.operations.size());
    uint32_t cursor = 0;
    // Commands already describe traversal order: emit structural operations while
    // importing attributes, without another JSON-field scan/lowering traversal.
    auto node = [&](auto&& self, size_t depth) -> void {
        if (depth > 512) throw std::length_error("Card nesting exceeds 512");
        const auto command = kind(cursor);
        if (command == 3) {
            const auto text = value(cursor);
            const auto token = scalar(Kind::string, cursor++);
            storage.operations.push_back({OpKind::text, {}, token});
            storage.counts.text_bytes += text.size();
            return;
        }
        if (command != 0) throw std::invalid_argument("Expected card element");
        const auto tag_index = cursor++;
        const auto tag = value(tag_index);
        const auto object = storage.begin(Kind::object);
        storage.scalar(Kind::key, "tag", Field::tag); scalar(Kind::string, tag_index);
        while (cursor < count && (kind(cursor) == 1 || kind(cursor) == 2)) {
            const auto metadata = kind(cursor);
            uint32_t attributes = UINT32_MAX;
            if (metadata == 1) {
                storage.scalar(Kind::key, "htmlAttributes"); attributes = storage.begin(Kind::object);
            }
            do {
                const auto name_index = cursor++;
                const auto name = value(name_index);
                if (cursor == count || kind(cursor) != 5) throw std::invalid_argument("Missing card value");
                scalar(Kind::key, name_index, field_name(name));
                scalar(Kind::string, cursor++);
            } while (cursor < count && kind(cursor) == metadata);
            if (attributes != UINT32_MAX) storage.end(attributes);
        }
        ++storage.counts.elements;
        if (tag == "ruby") ++storage.counts.ruby_groups;
        if (tag == "rt") ++storage.counts.ruby_readings;
        if (tag == "table") ++storage.counts.tables;
        if (tag == "tr") ++storage.counts.rows;
        if (tag == "td" || tag == "th") ++storage.counts.cells;
        if (tag == "ol" || tag == "ul") ++storage.counts.lists;
        storage.operations.push_back({tag == "rt" ? OpKind::reading_open : OpKind::open, {}, object});
        storage.scalar(Kind::key, "content", Field::content);
        if (cursor + 1 < count && kind(cursor) == 3 && kind(cursor + 1) == 4) {
            self(self, depth + 1);
        } else {
            const auto children = storage.begin(Kind::array);
            while (cursor < count && kind(cursor) != 4) self(self, depth + 1);
            storage.end(children);
        }
        if (cursor == count) throw std::invalid_argument("Unclosed card element");
        ++cursor;
        storage.end(object);
        storage.operations.push_back({tag == "rt" ? OpKind::reading_close : OpKind::close, {}, object});
    };
    while (cursor < count) node(node, 0);
    storage.end(root);
    storage.sources.push_back({root, checked_index(storage.tokens.size()), true,
        first_operation, checked_index(storage.operations.size())});
}
}

int dc_prepare_utf8(const dc_source* sources, size_t count, dc_document** output, dc_error* error) {
    return dc_prepare_mixed(sources, nullptr, count, output, error);
}
int dc_prepare_mixed(const dc_source* sources, const dc_typed_source *typed, size_t count, dc_document** output, dc_error* error) {
    if (output) *output = nullptr;
    if (error) *error = {};
    if (!output || (!sources && count)) {
        if (error) std::snprintf(error->message, sizeof(error->message), "Invalid batch arguments");
        return 1;
    }
    size_t source = 0;
    try {
        DC_PROFILE("prep.total");
        auto result = std::make_unique<dc_document>();
        auto& storage = result->storage;
        size_t bytes = 0;
        for (size_t i = 0; i < count; ++i) {
            if (!sources[i].utf8 && sources[i].bytes) throw std::invalid_argument("Null source");
            if (sources[i].bytes > UINT32_MAX - bytes) throw std::length_error("Batch exceeds storage limit");
            bytes += sources[i].bytes;
        }
        storage.strings.reserve(bytes / 2);
        storage.tokens.reserve(bytes / 8);
        storage.operations.reserve(bytes / 24);
        Handler handler(storage);
        rapidjson::Reader reader;
        for (; source < count; ++source) {
            if (typed && typed[source].data) {
                DC_PROFILE("prep.typed");
                typed_source(storage, typed[source]);
                continue;
            }
            std::string_view input(sources[source].utf8 ? sources[source].utf8 : "", sources[source].bytes);
            const auto first = input.find_first_not_of(" \n\r\t");
            const auto root = checked_index(storage.tokens.size());
            const bool json = first != std::string_view::npos && (input[first] == '[' || input[first] == '{');
            if (json) {
                DC_PROFILE("prep.json");
                rapidjson::MemoryStream stream(input.data(), input.size());
                if (!reader.Parse<rapidjson::kParseValidateEncodingFlag>(stream, handler)) {
                    if (error) error->offset = reader.GetErrorOffset();
                    throw std::runtime_error(rapidjson::GetParseError_En(reader.GetParseErrorCode()));
                }
            } else if(input.find('<') != std::string_view::npos) {
                DC_PROFILE("prep.html");
                html_source(storage,input);
                ++storage.counts.html_sources;
            } else {
                DC_PROFILE("prep.plain");
                storage.scalar(Kind::string,input);
                ++storage.counts.plain_sources;
            }
            storage.finish_source(root, json || input.find('<') != std::string_view::npos);
        }
        DC_PROFILE("prep.finish");
        storage.finish();
        *output = result.release();
        return 0;
    } catch (const std::exception& failure) {
        if (error) {
            error->source = static_cast<uint32_t>(source);
            std::snprintf(error->message, sizeof(error->message), "%s", failure.what());
        }
        return 1;
    } catch (...) {
        if (error) std::snprintf(error->message, sizeof(error->message), "Native preparation failed");
        return 1;
    }
}
dc_counts dc_document_counts(const dc_document* document) { return document ? document->storage.counts : dc_counts{}; }
uint64_t dc_document_digest(const dc_document* document) { return document ? document->storage.digest() : 0; }
void dc_document_destroy(dc_document* document) { delete document; }
void dc_profile_begin(int64_t id) { dc_profile::begin(id, false); }
void dc_profile_begin_deep(int64_t id) { dc_profile::begin(id, true); }
void dc_profile_end(void) { dc_profile::end(); }
