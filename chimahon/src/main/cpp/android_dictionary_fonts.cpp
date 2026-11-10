#include "android_dictionary_fonts.h"
#include <android/api-level.h>
#include <android/font.h>
#include <android/font_matcher.h>
#include <android/system_fonts.h>
#include <algorithm>
#include <dlfcn.h>
#include <memory>
#include <map>
#include <stdexcept>
#include <string>
#include <tuple>

namespace {
template<class T> T symbol(void *library, const char *name) {
    auto function = reinterpret_cast<T>(dlsym(library, name));
    if (!function) throw std::runtime_error(std::string("Missing system font API: ") + name);
    return function;
}
struct Catalog {
    struct File { std::string path; uint32_t index; int rank; };
    std::vector<File> files;
    std::vector<dc_font_file> view;
    Catalog() {
        if (android_get_device_api_level() < 30)
            throw std::runtime_error("Portable dictionary renderer requires Android 11 or newer");
        std::unique_ptr<void, decltype(&dlclose)> library(dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL), dlclose);
        if (!library) throw std::runtime_error("Cannot load system font catalog");
        auto open = symbol<ASystemFontIterator *(*)()>(library.get(), "ASystemFontIterator_open");
        auto next = symbol<AFont *(*)(ASystemFontIterator *)>(library.get(), "ASystemFontIterator_next");
        auto close = symbol<void (*)(ASystemFontIterator *)>(library.get(), "ASystemFontIterator_close");
        auto release = symbol<void (*)(AFont *)>(library.get(), "AFont_close");
        auto path = symbol<const char *(*)(const AFont *)>(library.get(), "AFont_getFontFilePath");
        auto index = symbol<size_t (*)(const AFont *)>(library.get(), "AFont_getCollectionIndex");
        auto locale = symbol<const char *(*)(const AFont *)>(library.get(), "AFont_getLocale");
        auto weight = symbol<uint16_t (*)(const AFont *)>(library.get(), "AFont_getWeight");
        auto italic = symbol<bool (*)(const AFont *)>(library.get(), "AFont_isItalic");
        auto createMatcher = symbol<AFontMatcher *(*)()>(library.get(), "AFontMatcher_create");
        auto destroyMatcher = symbol<void (*)(AFontMatcher *)>(library.get(), "AFontMatcher_destroy");
        auto setLocales = symbol<void (*)(AFontMatcher *, const char *)>(library.get(), "AFontMatcher_setLocales");
        auto match = symbol<AFont *(*)(const AFontMatcher *, const char *, const uint16_t *, uint32_t, uint32_t *)>(library.get(), "AFontMatcher_match");
        std::unique_ptr<AFontMatcher, decltype(destroyMatcher)> matcher(createMatcher(), destroyMatcher);
        if (!matcher) throw std::runtime_error("Cannot create system font matcher");
        setLocales(matcher.get(), "ja-JP");
        std::map<std::pair<std::string, uint32_t>, size_t> seen;
        auto add = [&](const AFont *font, int rank) {
            const auto *filename = path(font);
            if (!filename) return;
            const auto collection = static_cast<uint32_t>(index(font));
            const auto [entry, inserted] = seen.emplace(std::make_pair(std::string(filename), collection), files.size());
            if (inserted) files.push_back({filename, collection, rank});
            else files[entry->second].rank = std::min(files[entry->second].rank, rank);
        };
        // Enumeration includes named decorative families. Only the matcher knows
        // which files actually belong to the device's default sans-serif family.
        const std::vector<std::vector<uint16_t>> samples{{'A'}, {0x6f22}, {0xd83d, 0xde00}};
        for (size_t i = 0; i < samples.size(); ++i) {
            const auto &text = samples[i];
            std::unique_ptr<AFont, decltype(release)> font(match(matcher.get(), "sans-serif", text.data(), text.size(), nullptr), release);
            if (!font) throw std::runtime_error("Cannot match default system font");
            add(font.get(), -30000 + static_cast<int>(i));
        }
        std::unique_ptr<ASystemFontIterator, decltype(close)> iterator(open(), close);
        if (!iterator) throw std::runtime_error("Cannot enumerate system fonts");
        while (auto value = next(iterator.get())) {
            std::unique_ptr<AFont, decltype(release)> font(value, release);
            const auto *filename = path(font.get());
            if (!filename) continue;
            const std::string name(filename), languages = locale(font.get()) ? locale(font.get()) : "";
            const bool japanese = languages.find("ja") != std::string::npos;
            const bool emoji = name.find("Emoji") != std::string::npos;
            const bool neutral = languages.empty() || languages == "und";
            const bool symbols = name.find("Symbols") != std::string::npos;
            const int family = neutral && !symbols && !emoji ? 0 : japanese ? 1 : emoji ? 2 : 3;
            const int style = std::abs(static_cast<int>(weight(font.get())) - 400) + (italic(font.get()) ? 1000 : 0);
            const auto rank = family * 10000 + style;
            add(font.get(), rank);
        }
        std::stable_sort(files.begin(), files.end(), [](const auto &a, const auto &b) { return a.rank < b.rank; });
        if (files.empty()) throw std::runtime_error("System font catalog is empty");
        for (const auto &file : files) view.push_back({file.path.c_str(), file.index});
    }
};
}
const std::vector<dc_font_file> &dictionary_system_fonts() {
    static const Catalog catalog;
    return catalog.view;
}
