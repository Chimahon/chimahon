#include "android_dictionary_fonts.h"
#include "font_catalog_xml.hpp"
#include <android/api-level.h>
#include <android/font.h>
#include <android/font_matcher.h>
#include <android/system_fonts.h>
#include <dirent.h>
#include <dlfcn.h>
#include <unistd.h>
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
template<class T> T symbol(void *library, const char *name) {
    auto function = reinterpret_cast<T>(dlsym(library, name));
    if (!function) throw std::runtime_error(std::string("Missing system font API: ") + name);
    return function;
}

struct Catalog {
    struct File {
        std::string path;
        uint32_t index;
        int rank;
    };
    std::vector<File> files;
    std::vector<dc_font_file> view;

    explicit Catalog(const std::string &language) {
        // API 30 is the proven NDK font-matcher path. API 26-29 describe the same
        // catalog in fonts.xml; parse that instead of disabling the renderer.
        if (android_get_device_api_level() >= 30) {
            load_ndk(language);
        } else {
            load_xml(language);
        }
        if (files.empty()) throw std::runtime_error("System font catalog is empty");
        std::stable_sort(files.begin(), files.end(),
            [](const File &a, const File &b) { return a.rank < b.rank; });
        view.reserve(files.size());
        for (const auto &file : files) view.push_back({file.path.c_str(), file.index});
    }

private:
    /* API 30+: the platform matcher knows which files belong to the device's
     * default sans-serif family, including vendor overrides. */
    void load_ndk(const std::string &language) {
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
        setLocales(matcher.get(), language.c_str());
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
            const std::string primary = language.substr(0, 2);
            const bool preferred = languages.find(primary) != std::string::npos;
            const bool emoji = name.find("Emoji") != std::string::npos;
            const bool neutral = languages.empty() || languages == "und";
            const bool symbols = name.find("Symbols") != std::string::npos;
            const int family = neutral && !symbols && !emoji ? 0 : preferred ? 1 : emoji ? 2 : 3;
            const int style = std::abs(static_cast<int>(weight(font.get())) - 400) + (italic(font.get()) ? 1000 : 0);
            const auto rank = family * 10000 + style;
            add(font.get(), rank);
        }
    }

    /* API 26-29: fonts.xml (or the legacy system_fonts.xml) is the catalog. */
    void load_xml(const std::string &language) {
        static const char *const candidates[] = {
            "/system/etc/fonts.xml",
            "/system/etc/system_fonts.xml",
            "/vendor/etc/fonts.xml",
            "/product/etc/fonts.xml",
        };
        for (const char *candidate : candidates) {
            std::ifstream input(candidate, std::ios::binary);
            if (!input) continue;
            const std::string xml((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
            auto parsed = parse_fonts_xml(xml, "/system/fonts/", language);
            if (parsed.empty()) continue;
            files.reserve(parsed.size());
            for (auto &file : parsed) {
                if (access(file.path.c_str(), R_OK) != 0) continue;
                files.push_back({std::move(file.path), file.collection, static_cast<int>(files.size())});
            }
            return;
        }
        load_directory("/system/fonts");
    }

    /* Last resort for vendor images without a readable font description. */
    void load_directory(const char *directory) {
        DIR *dir = opendir(directory);
        if (!dir) return;
        const auto supported = [](std::string suffix) {
            for (auto &c : suffix) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
            return suffix == ".ttf" || suffix == ".otf" || suffix == ".ttc";
        };
        std::vector<std::string> names;
        while (const dirent *entry = readdir(dir)) {
            const std::string name = entry->d_name;
            if (name.size() > 4 && supported(name.substr(name.size() - 4))) names.push_back(name);
        }
        closedir(dir);
        std::sort(names.begin(), names.end());
        for (auto &name : names) {
            files.push_back({std::string(directory) + "/" + name, 0, static_cast<int>(files.size())});
        }
    }
};
}  // namespace

const std::vector<dc_font_file> &dictionary_system_fonts(const char *language) {
    static std::mutex mutex;
    static std::map<std::string, std::unique_ptr<Catalog>> catalogs;
    const std::string key = language && *language ? language : "ja";
    std::lock_guard<std::mutex> lock(mutex);
    auto &entry = catalogs[key];
    if (!entry) entry = std::make_unique<Catalog>(key);
    return entry->view;
}
