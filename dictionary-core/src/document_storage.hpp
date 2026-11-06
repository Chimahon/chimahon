#pragma once
#include "dictionary_core.h"
#include <array>
#include <charconv>
#include <cmath>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace dictionary_core {
enum class Kind : uint8_t { object, array, key, string, number, boolean, null_value };
enum class Field : uint8_t { other, type, tag, content, text, ruby, rt, data, style };
struct Token {
    Kind kind;
    Field field;
    uint16_t reserved = 0;
    uint32_t end, offset, length;
};
enum class OpKind : uint8_t { open, close, text, reading_open, reading_close };
struct Operation { OpKind kind; uint8_t reserved[3]{}; uint32_t token; };
struct SourceRange { uint32_t first, end; bool json; };

inline uint32_t checked_index(size_t size) {
    if (size >= UINT32_MAX) throw std::length_error("Document exceeds 32-bit storage limit");
    return static_cast<uint32_t>(size);
}
inline Field field_name(std::string_view name) {
    if (name == "content") return Field::content;
    if (name == "tag") return Field::tag;
    if (name == "type") return Field::type;
    if (name == "text") return Field::text;
    if (name == "ruby") return Field::ruby;
    if (name == "rt") return Field::rt;
    if (name == "data") return Field::data;
    if (name == "style") return Field::style;
    return Field::other;
}

struct Storage {
    std::vector<Token> tokens;
    std::string strings;
    std::vector<Operation> operations;
    std::vector<SourceRange> sources;
    dc_counts counts{};

    std::string_view value(uint32_t at) const {
        const auto& token = tokens.at(at);
        return std::string_view(strings).substr(token.offset, token.length);
    }
    uint32_t scalar(Kind kind, std::string_view text, Field field = Field::other) {
        const auto at = checked_index(tokens.size());
        const auto offset = checked_index(strings.size());
        checked_index(strings.size() + text.size());
        strings.append(text);
        tokens.push_back({kind, field, 0, at + 1, offset, checked_index(text.size())});
        return at;
    }
    template<class T> void number(T n) {
        std::array<char, 64> buffer;
        auto result = std::to_chars(buffer.data(), buffer.data() + buffer.size(), n);
        if (result.ec != std::errc{}) throw std::runtime_error("Number conversion failed");
        scalar(Kind::number, {buffer.data(), static_cast<size_t>(result.ptr - buffer.data())});
    }
    uint32_t begin(Kind kind) { return scalar(kind, {}); }
    void end(uint32_t at) { tokens[at].end = checked_index(tokens.size()); }

    // Input fields may arrive in any order. Metadata and unknown fields remain
    // on the tape; lowering only follows the content-bearing fields.
    std::array<uint32_t, 9> fields(uint32_t object) const {
        std::array<uint32_t, 9> found;
        found.fill(UINT32_MAX);
        for (auto at = object + 1; at < tokens[object].end;) {
            const auto key = at++;
            if (tokens[key].kind != Kind::key || at >= tokens[object].end)
                throw std::runtime_error("Invalid object tape");
            found[static_cast<uint8_t>(tokens[key].field)] = at;
            at = tokens[at].end;
        }
        return found;
    }
    void lower(uint32_t at, size_t depth = 0) {
        if (depth > 512) throw std::length_error("Glossary nesting exceeds 512");
        const auto& token = tokens[at];
        if (token.kind == Kind::array) {
            for (auto child = at + 1; child < token.end; child = tokens[child].end) lower(child, depth + 1);
            return;
        }
        if (token.kind == Kind::string || token.kind == Kind::number || token.kind == Kind::boolean) {
            operations.push_back({OpKind::text, {}, at});
            counts.text_bytes += token.length;
            return;
        }
        if (token.kind != Kind::object) return;
        const auto members = fields(at);
        const auto data = members[static_cast<uint8_t>(Field::data)];
        if(data != UINT32_MAX && tokens[data].kind == Kind::object) {
            const auto data_content = fields(data)[static_cast<uint8_t>(Field::content)];
            if(data_content != UINT32_MAX && value(data_content) == "attribution") return;
        }
        const auto tag = members[static_cast<uint8_t>(Field::tag)];
        const auto type = members[static_cast<uint8_t>(Field::type)];
        const auto text = members[static_cast<uint8_t>(Field::text)];
        const auto ruby = members[static_cast<uint8_t>(Field::ruby)];
        const auto content = members[static_cast<uint8_t>(Field::content)];
        const auto tag_name = tag == UINT32_MAX ? std::string_view{} : value(tag);
        const auto type_name = type == UINT32_MAX ? std::string_view{} : value(type);
        const bool wrapper = type_name == "structured-content";
        if (!wrapper) {
            ++counts.elements;
            if (tag_name == "ruby" || ruby != UINT32_MAX) ++counts.ruby_groups;
            if (tag_name == "rt") ++counts.ruby_readings;
            if (tag_name == "table") ++counts.tables;
            if (tag_name == "tr") ++counts.rows;
            if (tag_name == "td" || tag_name == "th") ++counts.cells;
            if (tag_name == "ol" || tag_name == "ul") ++counts.lists;
            operations.push_back({tag_name == "rt" ? OpKind::reading_open : OpKind::open, {}, at});
        }
        if (text != UINT32_MAX) lower(text, depth + 1);
        else if (ruby != UINT32_MAX && tokens[ruby].kind == Kind::object) {
            const auto ruby_members = fields(ruby);
            const auto base = ruby_members[static_cast<uint8_t>(Field::text)];
            const auto reading = ruby_members[static_cast<uint8_t>(Field::rt)];
            if (base != UINT32_MAX) lower(base, depth + 1);
            if (reading != UINT32_MAX) {
                ++counts.ruby_readings;
                operations.push_back({OpKind::reading_open, {}, reading});
                lower(reading, depth + 1);
                operations.push_back({OpKind::reading_close, {}, reading});
            }
        } else if (content != UINT32_MAX) lower(content, depth + 1);
        if (!wrapper) operations.push_back({tag_name == "rt" ? OpKind::reading_close : OpKind::close, {}, at});
    }
    void finish_source(uint32_t first, bool json) {
        sources.push_back({first, checked_index(tokens.size()), json});
        lower(first);
    }
    void finish() {
        counts.sources = sources.size();
        counts.json_tokens = tokens.size();
        counts.operations = operations.size();
    }
    uint64_t digest() const {
        uint64_t hash = 14695981039346656037ull;
        auto add = [&](uint64_t n) {
            for (unsigned i = 0; i < 8; ++i) { hash ^= (n >> (8 * i)) & 255; hash *= 1099511628211ull; }
        };
        for (const auto& t : tokens) {
            add(static_cast<uint8_t>(t.kind)); add(static_cast<uint8_t>(t.field));
            add(t.end); add(t.offset); add(t.length);
        }
        for (unsigned char c : strings) { hash ^= c; hash *= 1099511628211ull; }
        for (const auto& op : operations) { add(static_cast<uint8_t>(op.kind)); add(op.token); }
        for (const auto& source : sources) { add(source.first); add(source.end); add(source.json); }
        return hash;
    }
};
}

struct dc_document { dictionary_core::Storage storage; };
