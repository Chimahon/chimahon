#ifndef DC_CSS_COLOR_HPP
#define DC_CSS_COLOR_HPP
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <string_view>

namespace dictionary_core {
/* CSS colour parsing shared by the drawing plan and the prepared-document style
 * snapshot. Returns ARGB; `fallback` is returned for unsupported input. */
inline uint32_t css_color(std::string_view value, uint32_t fallback, unsigned depth = 0) {
    if (depth > 32)
        return fallback;
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
        {"brown", 0xffa52a2a},  {"crimson", 0xffdc143c}, {"goldenrod", 0xffdaa520}};
    for (auto n : names)
        if (value == n.name)
            return n.value;
    if (value.starts_with("color-mix(") && value.ends_with(')')) {
        auto trim = [](std::string_view s) {
            const auto first = s.find_first_not_of(" \r\n\t");
            return first == s.npos ? std::string_view{} : s.substr(first, s.find_last_not_of(" \r\n\t") - first + 1);
        };
        std::array<std::string_view, 3> parts{};
        size_t begin = 10, count = 0;
        unsigned nesting = 0;
        for (size_t i = begin; i < value.size() - 1; ++i) {
            if (value[i] == '(')
                ++nesting;
            if (value[i] == ')' && nesting)
                --nesting;
            if (value[i] == ',' && nesting == 0) {
                if (count >= 2)
                    return fallback;
                parts[count++] = trim(value.substr(begin, i - begin));
                begin = i + 1;
            }
        }
        if (count != 2 || parts[0] != "in srgb")
            return fallback;
        parts[2] = trim(value.substr(begin, value.size() - 1 - begin));
        float weights[]{-1, -1};
        uint32_t colors[2]{};
        for (int i = 0; i < 2; ++i) {
            auto part = parts[i + 1];
            if (part.ends_with('%')) {
                auto split = part.find_last_of(" \t\n");
                if (split == part.npos)
                    return fallback;
                std::string number(part.substr(split + 1));
                char *end = nullptr;
                const float parsed = std::strtof(number.c_str(), &end);
                if (end == number.c_str() || !std::isfinite(parsed))
                    return fallback;
                weights[i] = parsed / 100;
                if (weights[i] < 0)
                    return fallback;
                part = trim(part.substr(0, split));
            }
            colors[i] = css_color(part, fallback, depth + 1);
        }
        if (weights[0] < 0 && weights[1] < 0)
            weights[0] = weights[1] = .5f;
        else if (weights[0] < 0)
            weights[0] = std::max(0.f, 1 - weights[1]);
        else if (weights[1] < 0)
            weights[1] = std::max(0.f, 1 - weights[0]);
        const float sum = weights[0] + weights[1];
        if (sum <= 0)
            return 0;
        const float a = (colors[0] >> 24) / 255.f * weights[0] / sum, b = (colors[1] >> 24) / 255.f * weights[1] / sum;
        if (a + b <= 0)
            return 0;
        uint32_t result = static_cast<uint32_t>(std::lround((a + b) * std::min(1.f, sum) * 255)) << 24;
        for (int shift : {16, 8, 0})
            result |= static_cast<uint32_t>(std::lround((((colors[0] >> shift) & 255) * a + ((colors[1] >> shift) & 255) * b) / (a + b))) << shift;
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
/* Serialise ARGB as the 8-digit CSS form css_color reads (#RRGGBBAA). */
inline std::string css_color_text(uint32_t argb) {
    char text[10];
    std::snprintf(text, sizeof text, "#%02X%02X%02X%02X",
        (argb >> 16) & 255, (argb >> 8) & 255, argb & 255, argb >> 24);
    return text;
}
} // namespace dictionary_core
#endif
