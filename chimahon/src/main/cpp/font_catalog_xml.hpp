#pragma once
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <set>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

/* Android < 29 system font catalog.
 *
 * `fonts.xml` is the only system-font source below API 29: the NDK
 * ASystemFontIterator/AFontMatcher pair used on API 29+ does not exist there.
 * The file is a <familyset> of <family name lang> with <font weight style
 * index>path</font> children; relative paths are under /system/fonts, vendor
 * paths are absolute. Older devices also ship `system_fonts.xml`
 * (<family><name>... and <file>path</file>), which this reader accepts too.
 *
 * Order is the fallback order the portable renderer walks. The default
 * sans-serif family is emitted first (400 normal first), then every remaining
 * family in declaration order, 400 normal first inside each family. Entries
 * are deduplicated by file + collection index because variable fonts repeat
 * the same file for several weights.
 */

struct FontCatalogFile {
    std::string path;
    uint32_t collection = 0;
};

namespace font_catalog {

inline bool is_space(char value) {
    return std::isspace(static_cast<unsigned char>(value)) != 0;
}

inline std::string_view trim(std::string_view value) {
    while (!value.empty() && is_space(value.front())) value.remove_prefix(1);
    while (!value.empty() && is_space(value.back())) value.remove_suffix(1);
    return value;
}

inline bool starts_tag(std::string_view tag, std::string_view name) {
    return tag.size() >= name.size() && tag.substr(0, name.size()) == name &&
        (tag.size() == name.size() || is_space(tag[name.size()]) || tag[name.size()] == '/');
}

/* Reads `key="value"` / `key='value'`; `key` must begin a word inside the tag. */
inline std::string attribute(std::string_view tag, std::string_view key) {
    size_t at = 0;
    while ((at = tag.find(key, at)) != std::string_view::npos) {
        const bool word = at == 0 || is_space(tag[at - 1]) || tag[at - 1] == '<';
        size_t equal = at + key.size();
        while (equal < tag.size() && is_space(tag[equal])) ++equal;
        if (word && equal < tag.size() && tag[equal] == '=') {
            size_t quote = equal + 1;
            while (quote < tag.size() && is_space(tag[quote])) ++quote;
            if (quote < tag.size() && (tag[quote] == '"' || tag[quote] == '\'')) {
                const char delimiter = tag[quote++];
                const size_t end = tag.find(delimiter, quote);
                if (end != std::string_view::npos) return std::string(tag.substr(quote, end - quote));
            }
        }
        at += key.size();
    }
    return {};
}

/* Comma-separated family names / language lists. */
inline bool has_token(std::string_view list, std::string_view token) {
    size_t at = 0;
    while (at <= list.size()) {
        const size_t comma = list.find(',', at);
        const auto item = trim(list.substr(at, comma == std::string_view::npos ? list.size() : comma - at));
        if (item == token) return true;
        if (comma == std::string_view::npos) break;
        at = comma + 1;
    }
    return false;
}

inline std::string first_word(std::string_view text) {
    text = trim(text);
    size_t end = 0;
    while (end < text.size() && !is_space(text[end])) ++end;
    return std::string(text.substr(0, end));
}

inline std::string resolve_path(const std::string &path, std::string_view base) {
    if (path.empty()) return {};
    if (path.front() == '/') return path;
    if (path.rfind("system/", 0) == 0 || path.rfind("vendor/", 0) == 0 || path.rfind("product/", 0) == 0) {
        return "/" + path;
    }
    return std::string(base) + path;
}

struct XmlFont {
    std::string path;
    uint32_t collection = 0;
    int weight = 400;
    bool italic = false;
};

struct XmlFamily {
    std::string name;
    std::string lang;
    std::vector<XmlFont> fonts;
};

inline int weight_rank(const XmlFont &font) {
    return std::abs(font.weight - 400) + (font.italic ? 1000 : 0);
}

}  // namespace font_catalog

inline std::vector<FontCatalogFile> parse_fonts_xml(
    std::string_view xml, std::string_view base = "/system/fonts/",
    std::string_view language = "ja") {
    using namespace font_catalog;
    std::vector<XmlFamily> families;
    XmlFamily family;
    XmlFont font;
    bool in_family = false;
    bool in_font = false;
    bool capture_path = false;
    bool capture_name = false;
    size_t at = 0;

    while (at < xml.size()) {
        const size_t open = xml.find('<', at);
        if (open == std::string_view::npos) break;

        const auto text = trim(xml.substr(at, open - at));
        if (!text.empty()) {
            if (in_font && capture_path && font.path.empty()) {
                font.path = first_word(text);
                capture_path = false;
            } else if (in_family && capture_name && family.name.empty()) {
                family.name = std::string(text);
                capture_name = false;
            }
        }

        if (xml.substr(open, 4) == "<!--") {
            const size_t end = xml.find("-->", open + 4);
            if (end == std::string_view::npos) break;
            at = end + 3;
            continue;
        }

        const size_t close = xml.find('>', open + 1);
        if (close == std::string_view::npos) break;
        auto tag = trim(xml.substr(open + 1, close - open - 1));
        const bool closing = !tag.empty() && tag.front() == '/';
        const bool self_closing = !tag.empty() && tag.back() == '/';
        if (closing) tag.remove_prefix(1);
        if (self_closing) tag.remove_suffix(1);
        tag = trim(tag);

        if (!closing) {
            if (starts_tag(tag, "family")) {
                if (in_family) families.push_back(std::move(family));
                family = XmlFamily{};
                family.name = attribute(tag, "name");
                family.lang = attribute(tag, "lang");
                in_family = true;
                in_font = false;
                capture_path = false;
                capture_name = false;
                if (self_closing) {
                    families.push_back(std::move(family));
                    in_family = false;
                }
            } else if (in_family && (starts_tag(tag, "font") || starts_tag(tag, "file"))) {
                font = XmlFont{};
                const auto index = attribute(tag, "index");
                if (!index.empty()) font.collection = static_cast<uint32_t>(std::strtoul(index.c_str(), nullptr, 10));
                const auto weight = attribute(tag, "weight");
                if (!weight.empty()) font.weight = std::atoi(weight.c_str());
                font.italic = attribute(tag, "style") == "italic";
                in_font = true;
                capture_path = true;
                if (self_closing) {
                    in_font = false;
                    capture_path = false;
                }
            } else if (in_family && starts_tag(tag, "name")) {
                capture_name = !self_closing;
            }
        } else if (starts_tag(tag, "font") || starts_tag(tag, "file")) {
            if (in_family && !font.path.empty()) family.fonts.push_back(std::move(font));
            in_font = false;
            capture_path = false;
        } else if (starts_tag(tag, "name")) {
            capture_name = false;
        } else if (starts_tag(tag, "family")) {
            if (in_family) {
                families.push_back(std::move(family));
                in_family = false;
            }
            in_font = false;
            capture_path = false;
            capture_name = false;
        }

        at = close + 1;
    }
    if (in_family) families.push_back(std::move(family));

    std::vector<FontCatalogFile> out;
    std::set<std::pair<std::string, uint32_t>> seen;
    const auto emit = [&](const XmlFamily &source) {
        auto fonts = source.fonts;
        std::stable_sort(fonts.begin(), fonts.end(), [](const XmlFont &a, const XmlFont &b) {
            return weight_rank(a) < weight_rank(b);
        });
        for (const auto &item : fonts) {
            auto path = resolve_path(item.path, base);
            if (path.empty()) continue;
            if (seen.emplace(path, item.collection).second) {
                out.push_back({std::move(path), item.collection});
            }
        }
    };

    size_t default_family = families.size();
    for (size_t i = 0; i < families.size(); ++i) {
        if (has_token(families[i].name, "sans-serif")) {
            default_family = i;
            break;
        }
    }
    if (default_family == families.size() && !families.empty()) default_family = 0;

    /* Match the NDK matcher's locale: the default family first, then the
     * requested language, then emoji, then every remaining family in
     * declaration order. Without this, fonts.xml declares zh-Hans before ja,
     * so Japanese text would pick Simplified Chinese glyphs (and vice versa). */
    const auto language_rank = [&](const XmlFamily &family) -> int {
        if (language.empty()) return 3;
        if (has_token(family.lang, language)) return 0;
        if (language.size() >= 2 && family.lang.rfind(language.substr(0, 2), 0) == 0) return 1;
        if (language.rfind("zh-", 0) == 0 && family.lang.rfind("zh", 0) == 0) return 1;
        return 3;
    };
    std::vector<size_t> order;
    std::vector<bool> used(families.size(), false);
    const auto add_order = [&](size_t i) {
        if (i < families.size() && !used[i]) {
            used[i] = true;
            order.push_back(i);
        }
    };
    if (default_family < families.size()) add_order(default_family);
    for (int rank = 0; rank <= 1; ++rank) {
        for (size_t i = 0; i < families.size(); ++i) {
            if (language_rank(families[i]) == rank) add_order(i);
        }
    }
    for (size_t i = 0; i < families.size(); ++i) {
        if (families[i].lang.find("Zsye") != std::string::npos ||
            families[i].name.find("Emoji") != std::string::npos) {
            add_order(i);
        }
    }
    for (size_t i = 0; i < families.size(); ++i) add_order(i);
    for (const size_t i : order) emit(families[i]);
    return out;
}
