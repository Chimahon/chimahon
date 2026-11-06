#pragma once
#include "styled_document.h"
#include "css_numeric.hpp"
#include <unordered_map>

namespace dictionary_core {
class ResolvedStyles {
    struct Style {
        std::unordered_map<std::string_view, std::string_view> properties;
        mutable std::unordered_map<std::string_view, std::optional<CssLengthProgram>> lengths;
        float font;
    };
    std::vector<Style> styles;
    float root = 16;
public:
    explicit ResolvedStyles(dc_prepared_view v) : root(v.base_font_size_px > 0 ? v.base_font_size_px : 16.f) {
        styles.reserve(v.style_count);
        for (size_t i = 0; i < v.style_count; ++i) {
            const auto &input = v.styles[i];
            Style s{{}, {}, input.font_size_px};
            s.properties.reserve(input.property_count);
            for (size_t j = 0; j < input.property_count; ++j) {
                const auto &p = v.properties[input.first_property + j];
                s.properties.emplace(std::string_view(v.text + p.name_offset, p.name_bytes),
                    std::string_view(v.text + p.value_offset, p.value_bytes));
            }
            styles.push_back(std::move(s));
        }
    }
    std::string_view property(uint32_t style, std::string_view name) const {
        if (style >= styles.size()) return {};
        const auto &properties = styles[style].properties;
        const auto found = properties.find(name);
        return found == properties.end() ? std::string_view{} : found->second;
    }
    std::optional<float> length(uint32_t style, std::string_view value, float basis) const {
        if (value.empty() || value == "auto" || value == "normal") return {};
        auto &s = styles.at(style);
        auto found = s.lengths.find(value);
        if (found == s.lengths.end())
            found = s.lengths.emplace(value, CssNumeric(value, s.font, root, 0).compile()).first;
        return found->second ? found->second->evaluate(basis) : std::nullopt;
    }
};
const ResolvedStyles &resolved_styles(const dc_prepared_document *);
}
