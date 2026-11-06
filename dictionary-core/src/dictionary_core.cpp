#include "document_storage.hpp"
#include <rapidjson/reader.h>
#include <rapidjson/memorystream.h>
#include <rapidjson/error/en.h>
#include <cstdio>
#include <memory>
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
}

int dc_prepare_utf8(const dc_source* sources, size_t count, dc_document** output, dc_error* error) {
    if (output) *output = nullptr;
    if (error) *error = {};
    if (!output || (!sources && count)) {
        if (error) std::snprintf(error->message, sizeof(error->message), "Invalid batch arguments");
        return 1;
    }
    size_t source = 0;
    try {
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
            std::string_view input(sources[source].utf8 ? sources[source].utf8 : "", sources[source].bytes);
            const auto first = input.find_first_not_of(" \n\r\t");
            const auto root = checked_index(storage.tokens.size());
            const bool json = first != std::string_view::npos && (input[first] == '[' || input[first] == '{');
            if (json) {
                rapidjson::MemoryStream stream(input.data(), input.size());
                if (!reader.Parse<rapidjson::kParseValidateEncodingFlag>(stream, handler)) {
                    if (error) error->offset = reader.GetErrorOffset();
                    throw std::runtime_error(rapidjson::GetParseError_En(reader.GetParseErrorCode()));
                }
            } else if(input.find('<') != std::string_view::npos) {
                html_source(storage,input);
                ++storage.counts.html_sources;
            } else {
                storage.scalar(Kind::string,input);
                ++storage.counts.plain_sources;
            }
            storage.finish_source(root, json || input.find('<') != std::string_view::npos);
        }
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
