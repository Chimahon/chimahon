#pragma once
#include "styled_document.h"
#include "css_numeric.hpp"
#include <algorithm>
#include <array>
#include <memory>
#include <span>
#include <string>
#include <unordered_map>

namespace dictionary_core {
struct ComputedLength {
    enum class Kind : uint8_t { absent, invalid, constant, percent, expression };
    const CssLengthProgram *program = nullptr;
    float number = 0;
    Kind kind = Kind::absent;

    float evaluate(float basis, uint64_t &unresolved, float fallback = 0) const {
        if (kind == Kind::absent) return fallback;
        if (kind == Kind::constant) return number;
        std::optional<float> result;
        if (kind == Kind::percent) {
            const float n = number * basis / 100.f;
            if (std::isfinite(n)) result = n;
        } else if (kind == Kind::expression) result = program->evaluate(basis);
        if (result) return *result;
        ++unresolved;
        return fallback;
    }
};

struct ComputedEdges {
    struct Assignment { ComputedLength value; uint8_t sides; };
    std::vector<Assignment> assignments;

    std::array<float, 4> evaluate(float basis, uint64_t &unresolved) const {
        std::array<float, 4> result{};
        for (const auto &a : assignments) {
            if (a.value.kind == ComputedLength::Kind::invalid) { ++unresolved; continue; }
            if (a.value.kind == ComputedLength::Kind::absent) continue;
            const auto before = unresolved;
            const float n = a.value.evaluate(basis, unresolved);
            if (before != unresolved) continue;
            for (unsigned side = 0; side < 4; ++side)
                if (a.sides & (1u << side)) result[side] = n;
        }
        return result;
    }
};

struct ComputedBoxStyle {
    enum class VerticalAlign : uint8_t { top, middle, bottom };
    ComputedEdges margin, padding;
    ComputedLength border, width;
    std::array<ComputedLength, 4> border_sides;
    bool grid = false, shrink_to_fit = false, collapse_borders = false;
    VerticalAlign vertical_align = VerticalAlign::top;

    std::array<float, 4> border_widths(uint64_t &unresolved) const {
        const float common = border.evaluate(0, unresolved);
        std::array<float, 4> result;
        for (unsigned i = 0; i < 4; ++i) result[i] = border_sides[i].evaluate(0, unresolved, common);
        return result;
    }
};

class ResolvedStyles {
    struct Style {
        uint32_t first_property, property_count;
        mutable std::unordered_map<std::string_view, std::optional<CssLengthProgram>> lengths;
        mutable std::unique_ptr<ComputedBoxStyle> box;
        float font;
    };
    std::vector<Style> styles;
    std::span<const dc_style_property> properties;
    std::string_view text;
    float root = 16;

    const std::optional<CssLengthProgram> &length_program(uint32_t style, std::string_view value) const {
        auto &s = styles.at(style);
        auto found = s.lengths.find(value);
        if (found == s.lengths.end())
            found = s.lengths.emplace(value, CssNumeric(value, s.font, root, 0).compile()).first;
        return found->second;
    }
    ComputedLength computed_length(uint32_t style, std::string_view value) const {
        if (value.empty() || value == "auto" || value == "normal") return {};
        const auto &program = length_program(style, value);
        if (!program) return {nullptr, 0, ComputedLength::Kind::invalid};
        if (program->instructions.size() == 1) {
            const auto &i = program->instructions.front();
            if (i.op == CssLengthProgram::Op::constant) return {nullptr, i.value, ComputedLength::Kind::constant};
            if (i.op == CssLengthProgram::Op::percent) return {nullptr, i.value, ComputedLength::Kind::percent};
        }
        // unordered_map rehash preserves its elements' addresses. Programs belong
        // to this prepared document and outlive all of its typed box fields.
        return {&*program, 0, ComputedLength::Kind::expression};
    }
    ComputedEdges computed_edges(uint32_t style, std::string_view prefix) const {
        ComputedEdges result;
        const auto shorthand = property(style, prefix);
        std::array<std::string_view, 4> values;
        unsigned count = 0;
        size_t begin = 0;
        while (begin < shorthand.size() && count < 4) {
            while (begin < shorthand.size() && shorthand[begin] == ' ') ++begin;
            size_t end = begin;
            unsigned depth = 0;
            for (; end < shorthand.size(); ++end) {
                if (shorthand[end] == '(') ++depth;
                if (shorthand[end] == ')' && depth) --depth;
                if (shorthand[end] == ' ' && !depth) break;
            }
            if (end > begin) values[count++] = shorthand.substr(begin, end - begin);
            begin = end + 1;
        }
        for (unsigned i = 0; i < count; ++i) {
            const unsigned indices[]{0, count > 1 ? 1u : 0u, count > 2 ? 2u : 0u,
                count > 3 ? 3u : count > 1 ? 1u : 0u};
            uint8_t sides = 0;
            for (unsigned side = 0; side < 4; ++side) if (indices[side] == i) sides |= 1u << side;
            result.assignments.push_back({computed_length(style, values[i]), sides});
        }
        constexpr std::string_view suffixes[]{"-top", "-right", "-bottom", "-left",
            "-block-start", "-block-end", "-inline-start", "-inline-end"};
        constexpr uint8_t sides[]{1, 2, 4, 8, 1, 4, 8, 2};
        for (unsigned i = 0; i < 8; ++i) {
            const auto value = computed_length(style, property(style, std::string(prefix) + std::string(suffixes[i])));
            if (value.kind != ComputedLength::Kind::absent) result.assignments.push_back({value, sides[i]});
        }
        return result;
    }
public:
    explicit ResolvedStyles(dc_prepared_view v) : properties(v.properties, v.property_count), text(v.text, v.text_bytes),
        root(v.base_font_size_px > 0 ? v.base_font_size_px : 16.f) {
        styles.reserve(v.style_count);
        for (size_t i = 0; i < v.style_count; ++i) {
            const auto &input = v.styles[i];
            styles.push_back({input.first_property, input.property_count, {}, nullptr, input.font_size_px});
        }
    }
    std::string_view property(uint32_t style, std::string_view name) const {
        if (style >= styles.size()) return {};
        const auto &s = styles[style];
        // Style properties are emitted sorted by name; image properties are separate ranges.
        const auto range = properties.subspan(s.first_property, s.property_count);
        const auto found = std::lower_bound(range.begin(), range.end(), name, [&](const auto& p, std::string_view key) {
            return text.substr(p.name_offset, p.name_bytes) < key;
        });
        if (found == range.end() || text.substr(found->name_offset, found->name_bytes) != name) return {};
        return text.substr(found->value_offset, found->value_bytes);
    }
    std::optional<float> length(uint32_t style, std::string_view value, float basis) const {
        if (value.empty() || value == "auto" || value == "normal") return {};
        const auto &program = length_program(style, value);
        return program ? program->evaluate(basis) : std::nullopt;
    }
    const ComputedBoxStyle &box(uint32_t style) const {
        auto &s = styles.at(style);
        if (s.box) return *s.box;
        auto box = std::make_unique<ComputedBoxStyle>();
        box->margin = computed_edges(style, "margin");
        box->padding = computed_edges(style, "padding");
        box->width = computed_length(style, property(style, "width"));
        const auto display = property(style, "display"), align = property(style, "vertical-align");
        box->grid = display == "grid";
        box->shrink_to_fit = display == "inline-block" || property(style, "width") == "fit-content";
        box->collapse_borders = property(style, "border-collapse") == "collapse";
        box->vertical_align = align == "middle" ? ComputedBoxStyle::VerticalAlign::middle :
            align == "bottom" ? ComputedBoxStyle::VerticalAlign::bottom : ComputedBoxStyle::VerticalAlign::top;
        if (property(style, "border-style") != "none" && property(style, "border") != "none") {
            const auto width = property(style, "border-width");
            if (!width.empty()) box->border = computed_length(style, width);
            else {
                const auto value = property(style, "border");
                size_t at = 0;
                while (at < value.size()) {
                    while (at < value.size() && value[at] == ' ') ++at;
                    auto end = value.find(' ', at);
                    if (end == value.npos) end = value.size();
                    const auto token = value.substr(at, end - at);
                    std::optional<float> n;
                    if (token == "thin") n = 1;
                    else if (token == "medium") n = 3;
                    else if (token == "thick") n = 5;
                    else n = CssNumeric(token, s.font, root, 0).evaluate(false);
                    if (n) { box->border = {nullptr, std::max(0.f, *n), ComputedLength::Kind::constant}; break; }
                    at = end + 1;
                }
            }
        }
        constexpr std::string_view sides[]{"border-top-width", "border-right-width", "border-bottom-width", "border-left-width"};
        for (unsigned i = 0; i < 4; ++i) box->border_sides[i] = computed_length(style, property(style, sides[i]));
        s.box = std::move(box);
        return *s.box;
    }
};
const ResolvedStyles &resolved_styles(const dc_prepared_document *);
}
