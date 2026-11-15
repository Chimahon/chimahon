#include "portable_renderer.h"
#include "profile.hpp"
#include "portable_color_glyph.hpp"
#include <hb.h>
#include <hb-ot.h>
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_OUTLINE_H
#include FT_TRUETYPE_TABLES_H
#include <SheenBidi/SheenBidi.h>
#include <linebreak.h>
#include <graphemebreak.h>
#include <agg_conv_curve.h>
#include <agg_conv_transform.h>
#include <agg_ellipse.h>
#include <agg_path_storage.h>
#include <agg_pixfmt_rgba.h>
#include <agg_rasterizer_scanline_aa.h>
#include <agg_renderer_scanline.h>
#include <agg_rounded_rect.h>
#include <agg_scanline_u.h>
#include <agg_span_allocator.h>
#include <agg_span_gradient.h>
#include <agg_span_interpolator_linear.h>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <climits>
#include <cstdio>
#include <array>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <string_view>
#include <thread>
#include <tuple>
#include <type_traits>
#include <vector>

namespace {
void require(bool value, const char *message) { if (!value) throw std::runtime_error(message); }
/* Family names arrive from CSS and from the OpenType name table. Compare them
 * trimmed, unquoted and lowercased so "Noto Sans CJK JP" matches its face. */
std::string normalize_family(std::string_view value) {
    size_t first = 0, last = value.size();
    while (first < last && std::isspace(static_cast<unsigned char>(value[first]))) ++first;
    while (last > first && std::isspace(static_cast<unsigned char>(value[last - 1]))) --last;
    if (last - first >= 2 && (value[first] == '"' || value[first] == '\'') && value[last - 1] == value[first]) {
        ++first; --last;
        while (first < last && std::isspace(static_cast<unsigned char>(value[first]))) ++first;
        while (last > first && std::isspace(static_cast<unsigned char>(value[last - 1]))) --last;
    }
    std::string out;
    out.reserve(last - first);
    for (size_t i = first; i < last; ++i)
        out.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(value[i]))));
    return out;
}
/* Generic families have no single installed face; keep the language-ranked
 * fallback instead of guessing one. */
bool generic_family(std::string_view name) {
    return name == "serif" || name == "sans-serif" || name == "monospace" ||
        name == "cursive" || name == "fantasy" || name == "system-ui" ||
        name == "-apple-system" || name == "blinkmacsystemfont" || name == "ui-serif" ||
        name == "ui-sans-serif" || name == "ui-monospace" || name == "ui-rounded" ||
        name == "emoji" || name == "math" || name == "inherit" || name == "initial" ||
        name == "unset" || name == "revert";
}
uint32_t codepoint(const uint16_t *text, size_t count, size_t at) {
    const uint32_t a = text[at];
    return a >= 0xd800 && a <= 0xdbff && at + 1 < count && text[at + 1] >= 0xdc00 && text[at + 1] <= 0xdfff
        ? 0x10000 + ((a - 0xd800) << 10) + text[at + 1] - 0xdc00 : a;
}
uint16_t be16(const unsigned char *p) { return static_cast<uint16_t>((p[0] << 8) | p[1]); }
void append_utf8(std::string &out, uint32_t cp) {
    if (cp < 0x80) out.push_back(static_cast<char>(cp));
    else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xc0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xe0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else {
        out.push_back(static_cast<char>(0xf0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    }
}
/* HarfBuzz's name API is not available in every minimal build, so read the
 * OpenType name table directly: typographic family (16), then family (1). */
std::string family_from_name_table(hb_face_t *face) {
    auto *blob = hb_face_reference_table(face, HB_TAG('n', 'a', 'm', 'e'));
    if (!blob) return {};
    unsigned length = 0;
    const auto *data = reinterpret_cast<const unsigned char *>(hb_blob_get_data(blob, &length));
    std::string family;
    int best = 100;
    if (data && length >= 6) {
        const uint16_t count = be16(data + 2), storage = be16(data + 4);
        for (uint16_t i = 0; i < count; ++i) {
            const auto *record = data + 6 + static_cast<size_t>(i) * 12;
            if (record + 12 > data + length) break;
            const uint16_t platform = be16(record), encoding = be16(record + 2);
            const uint16_t language = be16(record + 4), name = be16(record + 6);
            const uint16_t bytes = be16(record + 8), offset = be16(record + 10);
            if (name != 1 && name != 16) continue;
            const size_t start = static_cast<size_t>(storage) + offset;
            if (start + bytes > length) continue;
            std::string value;
            if (platform == 3 && (encoding == 1 || encoding == 10)) {
                for (size_t at = 0; at + 1 < bytes; at += 2) {
                    uint32_t cp = be16(data + start + at);
                    if (cp >= 0xd800 && cp <= 0xdbff && at + 3 < bytes) {
                        const uint32_t low = be16(data + start + at + 2);
                        if (low >= 0xdc00 && low <= 0xdfff) {
                            cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00);
                            at += 2;
                        }
                    }
                    append_utf8(value, cp);
                }
            } else if (platform == 1 && encoding == 0) {
                value.assign(reinterpret_cast<const char *>(data + start), bytes);
            } else continue;
            if (const auto nul = value.find('\0'); nul != std::string::npos) value.resize(nul);
            auto candidate = normalize_family(value);
            if (candidate.empty()) continue;
            // Prefer typographic family, then English/neutral language records.
            const int priority = (name == 16 ? 0 : 10) + (language == 0x409 || language == 0 ? 0 : 1);
            if (priority < best) {
                best = priority;
                family = std::move(candidate);
            }
        }
    }
    hb_blob_destroy(blob);
    return family;
}
struct FontData {
    hb_blob_t *blob = nullptr;
    hb_face_t *face = nullptr;
    /* Immutable probe font: coverage and color questions are answered without
     * parsing a FreeType face, which is what made font selection the largest
     * native phase (a Japanese lookup opened every font in the catalog). */
    hb_font_t *probe = nullptr;
    bool color = false;
    std::string family;
    std::once_flag ready;
    ~FontData() { if (probe) hb_font_destroy(probe); if (face) hb_face_destroy(face); if (blob) hb_blob_destroy(blob); }
    void load(const std::string &path, uint32_t collection) {
        std::call_once(ready, [&] {
            auto *mapped = hb_blob_create_from_file_or_fail(path.c_str());
            require(mapped != nullptr, "Cannot map portable font");
            auto *created = hb_face_create(mapped, collection);
            hb_face_make_immutable(created);
            blob = mapped;
            face = created;
            probe = hb_font_create(face);
            hb_ot_font_set_funcs(probe);
            family = family_from_name_table(face);
            hb_tag_t tags[64];
            unsigned count = 64;
            hb_face_get_table_tags(face, 0, &count, tags);
            for (unsigned i = 0; i < count; ++i)
                color |= tags[i] == HB_TAG('C', 'O', 'L', 'R') || tags[i] == HB_TAG('C', 'B', 'D', 'T') ||
                         tags[i] == HB_TAG('s', 'b', 'i', 'x') || tags[i] == HB_TAG('S', 'V', 'G', ' ');
        });
    }
};
std::shared_ptr<FontData> font_data(const std::string &path, uint32_t collection) {
    static std::mutex mutex;
    static std::map<std::pair<std::string, uint32_t>, std::shared_ptr<FontData>> files;
    std::shared_ptr<FontData> result;
    {
        std::lock_guard<std::mutex> lock(mutex);
        auto &entry = files[{path, collection}];
        if (!entry) entry = std::make_shared<FontData>();
        result = entry;
    }
    // Map/initialize different files concurrently, without holding the catalog lock.
    result->load(path, collection);
    return result;
}
struct Face {
    FT_Library library;
    std::string path;
    uint32_t collection;
    FT_Face ft = nullptr;
    hb_font_t *hb = nullptr;
    std::shared_ptr<FontData> data;
    std::shared_ptr<struct CachedFace> cached;
    /* A document is shaped on a preparation worker and later rasterised on the view thread. The
       FreeType face cache is thread-local, so a face resolved on one thread must not be used on
       another; remember the owner and resolve again when the thread changes. */
    std::thread::id owner;
    Face(FT_Library library, const dc_font_file &file) : library(library), path(file.path ? file.path : ""), collection(file.collection_index) {}
    /* Coverage and color come from the shared immutable HarfBuzz face, so
     * selecting a font never parses a FreeType face. */
    FontData &font() {
        if (!data) data = font_data(path, collection);
        return *data;
    }
    bool covers(uint32_t cp) {
        unsigned glyph = 0;
        return hb_font_get_nominal_glyph(font().probe, cp, &glyph) && glyph;
    }
    bool color() { return font().color; }
    const std::string &family() { return font().family; }
    void load();
    ~Face() = default;
};
/* One FT_Face per file and collection, kept for the life of the thread. Parsing
 * a CJK face costs milliseconds and every render batch used to pay it again for
 * every font, which made measurement the largest native phase on the device.
 * Faces are stateful (size, synthetic slant) but every use configures them
 * first, and a thread only ever runs one document at a time. */
struct CachedFace {
    FT_Face ft = nullptr;
    hb_font_t *hb = nullptr;
    ~CachedFace() {
        if (hb) hb_font_destroy(hb);
        if (ft) FT_Done_Face(ft);
    }
};
struct FontCache {
    FT_Library library = nullptr;
    std::map<std::pair<std::string, uint32_t>, std::shared_ptr<CachedFace>> faces;
    ~FontCache() {
        faces.clear();
        if (library) FT_Done_FreeType(library);
    }
    std::shared_ptr<CachedFace> face(const std::string &path, uint32_t collection) {
        auto &entry = faces[{path, collection}];
        if (entry) return entry;
        require(!path.empty(), "Missing portable font path");
        if (!library) require(FT_Init_FreeType(&library) == 0, "Cannot initialize FreeType");
        auto loaded = std::make_shared<CachedFace>();
        const auto data = font_data(path, collection);
        unsigned bytes = 0;
        const auto *memory = reinterpret_cast<const FT_Byte *>(hb_blob_get_data(data->blob, &bytes));
        {
            DC_PROFILE("fonts.open");
            dc_profile::count("fonts.faces");
            require(bytes <= LONG_MAX && FT_New_Memory_Face(library, memory, bytes, collection, &loaded->ft) == 0,
                "Cannot open portable font");
        }
        loaded->hb = hb_font_create(data->face);
        hb_ot_font_set_funcs(loaded->hb);
        entry = std::move(loaded);
        return entry;
    }
};
FontCache &font_cache() {
    static thread_local FontCache cache;
    return cache;
}
void Face::load() {
    const auto current = std::this_thread::get_id();
    if (cached && owner == current) return;
    require(!path.empty(), "Missing portable font path");
    cached = font_cache().face(path, collection);
    ft = cached->ft;
    hb = cached->hb;
    owner = current;
}
struct Glyph { uint32_t id, cluster, face; float advance, dx, dy; };
struct GlyphVariant { uint32_t id; float dx, dy; };
struct Run { uint32_t start, end, style; bool rtl; std::vector<Glyph> glyphs; };
using PathKey = std::tuple<uint32_t, uint32_t, uint32_t, uint32_t>;
using PixelFormat = agg::pixfmt_rgba32_pre;
using Renderer = agg::renderer_base<PixelFormat>;
agg::rgba8 color(uint32_t argb) {
    return agg::rgba8((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, argb >> 24).premultiply();
}
struct Outline {
    agg::path_storage path;
    double x = 0, y = 0;
    bool contour = false;
    static int move(const FT_Vector *to, void *self) {
        auto &o = *static_cast<Outline *>(self);
        if (o.contour) o.path.close_polygon();
        o.path.move_to(o.x = to->x / 64., o.y = -to->y / 64.);
        o.contour = true; return 0;
    }
    static int line(const FT_Vector *to, void *self) {
        auto &o = *static_cast<Outline *>(self);
        o.path.line_to(o.x = to->x / 64., o.y = -to->y / 64.); return 0;
    }
    static int conic(const FT_Vector *control, const FT_Vector *to, void *self) {
        auto &o = *static_cast<Outline *>(self);
        o.path.curve3(control->x / 64., -control->y / 64., o.x = to->x / 64., o.y = -to->y / 64.); return 0;
    }
    static int cubic(const FT_Vector *a, const FT_Vector *b, const FT_Vector *to, void *self) {
        auto &o = *static_cast<Outline *>(self);
        o.path.curve4(a->x / 64., -a->y / 64., b->x / 64., -b->y / 64., o.x = to->x / 64., o.y = -to->y / 64.); return 0;
    }
};
}

struct dc_portable_document {
    dc_drawing_plan *plan;
    FT_Library library = nullptr;
    std::vector<std::unique_ptr<Face>> faces;
    std::vector<Run> runs;
    std::map<PathKey, agg::path_storage> paths;
    std::map<PathKey, GlyphVariant> half_variants;
    std::map<uint32_t, uint32_t> coverage;
    std::vector<uint32_t> text_faces;
    std::map<std::tuple<uint32_t,uint32_t,uint32_t,uint32_t>, PortableColorGlyph> color_glyphs;
    std::string language;
    bool measured = false;
    dc_portable_document(dc_drawing_plan *p, const dc_font_file *files, size_t count, const char *language)
        : plan(p), language(language && *language ? language : "ja") {
        require(p && files && count, "Portable renderer needs a drawing plan and font files");
        faces.reserve(count);
        for (size_t i = 0; i < count; ++i) faces.push_back(std::make_unique<Face>(library, files[i]));
    }
    ~dc_portable_document() { faces.clear(); }
    /* Direct-mapped front cache: most text is a few hundred distinct code points,
     * so this avoids the hash lookup on nearly every grapheme. */
    std::array<uint32_t, 512> face_keys{};
    std::array<uint32_t, 512> face_values{};
    uint32_t face_for(uint32_t cp) {
        const uint32_t slot = cp & 511;
        if (face_keys[slot] == cp + 1) return face_values[slot];
        if (const auto found = coverage.find(cp); found != coverage.end()) {
            face_keys[slot] = cp + 1;
            face_values[slot] = found->second;
            return found->second;
        }
        uint32_t selected = 0;
        for (uint32_t i = 0; i < faces.size(); ++i) {
            if (faces[i]->covers(cp)) { selected = i; break; }
        }
        coverage.emplace(cp, selected);
        face_keys[slot] = cp + 1;
        face_values[slot] = selected;
        return selected;
    }
    /* CSS font-family is resolved per style into face indexes in the order the
       dictionary listed the families. A missing or generic family keeps the
       language-ranked fallback order. */
    std::vector<std::vector<uint32_t>> family_faces;
    bool families_indexed = false;
    void index_families() {
        if (families_indexed) return;
        families_indexed = true;
        const auto v = dc_drawing_plan_view(plan);
        family_faces.assign(v.font_count, {});
        if (!v.font_count || !v.names || !v.names_bytes) return;
        std::vector<std::string> names;
        names.reserve(faces.size());
        for (auto &face : faces) names.push_back(face->family());
        for (uint32_t style = 0; style < v.font_count; ++style) {
            const auto &font = v.fonts[style];
            if (!font.family_bytes || font.family_offset >= v.names_bytes) continue;
            const size_t bytes = std::min<size_t>(font.family_bytes, v.names_bytes - font.family_offset);
            const std::string_view list(v.names + font.family_offset, bytes);
            for (size_t at = 0; at < list.size();) {
                const auto comma = list.find(',', at);
                auto item = list.substr(at, comma == std::string_view::npos ? std::string_view::npos : comma - at);
                if (const auto bang = item.find('!'); bang != std::string_view::npos) item = item.substr(0, bang);
                const auto family = normalize_family(item);
                if (!family.empty()) {
                    /* Concrete families match by name. Generic serif/monospace still map to
                       installed faces, so a dictionary that falls back to `serif` (common for
                       example sentences) gets a Mincho-like face like the WebView does. */
                    const bool want_serif = family == "serif" || family == "ui-serif";
                    const bool want_mono = family == "monospace" || family == "ui-monospace";
                    for (uint32_t i = 0; i < names.size(); ++i) {
                        const bool match = (!generic_family(family) && names[i] == family) ||
                            (want_serif && names[i].find("serif") != std::string::npos &&
                                names[i].find("sans serif") == std::string::npos) ||
                            (want_mono && (names[i].find("mono") != std::string::npos ||
                                names[i].find("courier") != std::string::npos));
                        if (!match) continue;
                        auto &want = family_faces[style];
                        if (std::find(want.begin(), want.end(), i) == want.end()) want.push_back(i);
                    }
                }
                if (comma == std::string_view::npos) break;
                at = comma + 1;
            }
        }
    }
    uint32_t face_for_style(uint32_t cp, uint32_t style) {
        if (style < family_faces.size())
            for (const uint32_t i : family_faces[style])
                if (faces[i]->covers(cp)) return i;
        return face_for(cp);
    }
    uint32_t cluster_face(const uint16_t *text, uint32_t start, uint32_t end, uint32_t style) {
        const auto first = codepoint(text, end, start);
        if (start + (first > 0xffff ? 2 : 1) == end) return face_for_style(first, style);
        bool emoji = false, monochrome = false;
        for (auto at = start; at < end; ++at) { emoji |= text[at] == 0xfe0f; monochrome |= text[at] == 0xfe0e; }
        const auto covers = [&](uint32_t i, unsigned pass) {
            const bool color = faces[i]->color();
            if (!pass && ((emoji && !color) || (monochrome && color))) return false;
            for (auto at = start; at < end;) {
                const auto cp = codepoint(text, end, at);
                at += cp > 0xffff ? 2 : 1;
                if (cp == 0xfe0e || cp == 0xfe0f || (cp >= 0xe0100 && cp <= 0xe01ef) ||
                    hb_unicode_general_category(hb_unicode_funcs_get_default(), cp) == HB_UNICODE_GENERAL_CATEGORY_FORMAT) continue;
                if (!faces[i]->covers(cp)) return false;
            }
            return true;
        };
        for (unsigned pass = 0; pass < 2; ++pass) {
            if (style < family_faces.size())
                for (const uint32_t i : family_faces[style])
                    if (covers(i, pass)) return i;
            for (uint32_t i = 0; i < faces.size(); ++i)
                if (covers(i, pass)) return i;
        }
        return face_for_style(first, style);
    }
    uint32_t configured_face = UINT32_MAX;
    uint32_t configured_key = 0;
    float configured_size = 0;
    void configure(uint32_t i, const dc_drawing_font &font) {
        faces[i]->load(); // hb_font_t is created here; shaping may pick a face metrics did not preload
        require(faces[i]->hb != nullptr, "Cannot load portable font");
        const uint32_t key = (font.flags & 1) | (font.weight >= 600 ? 2 : 0);
        if (i == configured_face && key == configured_key && font.size == configured_size) return;
        hb_font_set_scale(faces[i]->hb, std::lround(font.size * 65536), std::lround(font.size * 65536));
        hb_font_set_synthetic_slant(faces[i]->hb, font.flags & 1 ? .25f : 0.f);
        // Match synthetic bold without changing the requested font file/family.
        hb_font_set_synthetic_bold(faces[i]->hb, font.weight >= 600 ? .025f : 0.f, 0.f, true);
        configured_face = i;
        configured_key = key;
        configured_size = font.size;
    }
    const GlyphVariant &half_variant(uint32_t face, uint32_t cp, const dc_drawing_font &font) {
        const PathKey key{face, cp, static_cast<uint32_t>(std::lround(font.size * 64)),
            (font.flags & 1) | (font.weight >= 600 ? 2 : 0)};
        if (const auto found = half_variants.find(key); found != half_variants.end()) return found->second;
        configure(face, font);
        auto buffer = hb_buffer_create();
        hb_buffer_add_codepoints(buffer, &cp, 1, 0, 1);
        hb_buffer_set_direction(buffer, HB_DIRECTION_LTR);
        hb_buffer_set_language(buffer, hb_language_from_string(language.c_str(), -1));
        hb_buffer_guess_segment_properties(buffer);
        const hb_feature_t feature{HB_TAG('h','a','l','t'), 1, 0, UINT_MAX};
        hb_shape(faces[face]->hb, buffer, &feature, 1);
        unsigned count;
        const auto info = hb_buffer_get_glyph_infos(buffer, &count);
        const auto position = hb_buffer_get_glyph_positions(buffer, nullptr);
        const GlyphVariant result = count ? GlyphVariant{info[0].codepoint,
            position[0].x_offset / 65536.f, -position[0].y_offset / 65536.f} : GlyphVariant{0, 0, 0};
        hb_buffer_destroy(buffer);
        return half_variants.emplace(key, result).first->second;
    }
    float bitmap_scale(FT_Face face, float size) {
        if (!FT_HAS_FIXED_SIZES(face) || FT_IS_SCALABLE(face)) return 1.f;
        float best = 0;
        int closest = INT_MAX;
        for (int i = 0; i < face->num_fixed_sizes; ++i) {
            const int ppem = face->available_sizes[i].y_ppem >> 6;
            const int distance = std::abs(ppem - static_cast<int>(std::lround(size)));
            if (distance < closest) { closest = distance; best = static_cast<float>(ppem); }
        }
        return best > 0 ? size / best : 1.f;
    }
    bool blit(uint32_t face_index, uint32_t glyph, const dc_drawing_font &font, float x, float y,
              float scale, float sx, float sy, uint32_t width, uint32_t height, uint8_t *pixels, size_t stride) {
        faces[face_index]->load();
        faces[face_index]->load();
        auto face = faces[face_index]->ft;
        if (!FT_HAS_COLOR(face)) return false;
        auto *hb = faces[face_index]->hb;
        unsigned layers = 0;
        if (hb_ot_color_glyph_has_paint(hb_font_get_face(hb), glyph) ||
            hb_ot_color_glyph_get_layers(hb_font_get_face(hb), glyph, 0, &layers, nullptr)) {
            const auto size = static_cast<uint32_t>(std::lround(font.size * scale * 64));
            const auto key = std::make_tuple(face_index, glyph, size, font.color);
            auto found = color_glyphs.find(key);
            if (found == color_glyphs.end())
                found = color_glyphs.emplace(key, portable_color_glyph(hb, glyph, size / 64.f, font.color)).first;
            const auto &image = found->second;
            const int left = static_cast<int>(std::lround((x-sx)*scale)) + image.left;
            const int top = static_cast<int>(std::lround((y-sy)*scale)) + image.top;
            for (int row = std::max(0,-top); row < std::min(image.height,static_cast<int>(height)-top); ++row) {
                auto *dst = pixels + static_cast<size_t>(top+row)*stride;
                const auto *src = image.bgra.data() + static_cast<size_t>(row)*image.width*4;
                for (int col = std::max(0,-left); col < std::min(image.width,static_cast<int>(width)-left); ++col) {
                    const auto *s = src + col*4;
                    auto *d = dst + (left+col)*4;
                    const unsigned inverse = 255-s[3];
                    d[0] = static_cast<uint8_t>(s[2]+(d[0]*inverse+127)/255);
                    d[1] = static_cast<uint8_t>(s[1]+(d[1]*inverse+127)/255);
                    d[2] = static_cast<uint8_t>(s[0]+(d[2]*inverse+127)/255);
                    d[3] = static_cast<uint8_t>(s[3]+(d[3]*inverse+127)/255);
                }
            }
            return true;
        }
        if (FT_Set_Char_Size(face, 0, std::lround(font.size * 64), 72, 72) != 0 &&
            (!FT_HAS_FIXED_SIZES(face) || FT_Select_Size(face, 0) != 0)) return false;
        if (FT_Load_Glyph(face, glyph, FT_LOAD_COLOR | FT_LOAD_NO_HINTING) != 0) return false;
        const auto &slot = face->glyph;
        if (slot->bitmap.pixel_mode != FT_PIXEL_MODE_BGRA || !slot->bitmap.buffer ||
            !slot->bitmap.width || !slot->bitmap.rows) return false;
        const float bs = bitmap_scale(face, font.size);
        const float left = x + slot->bitmap_left * bs, top = y - slot->bitmap_top * bs;
        const float dw = slot->bitmap.width * bs * scale, dh = slot->bitmap.rows * bs * scale;
        const float vx = (left - sx) * scale, vy = (top - sy) * scale;
        const int x0 = std::max(0, static_cast<int>(std::floor(vx)));
        const int y0 = std::max(0, static_cast<int>(std::floor(vy)));
        const int x1 = std::min(static_cast<int>(width), static_cast<int>(std::ceil(vx + dw)));
        const int y1 = std::min(static_cast<int>(height), static_cast<int>(std::ceil(vy + dh)));
        const float ux = slot->bitmap.width / dw, uy = slot->bitmap.rows / dh;
        const int pitch = slot->bitmap.pitch, bw = static_cast<int>(slot->bitmap.width), bh = static_cast<int>(slot->bitmap.rows);
        for (int py = y0; py < y1; ++py) {
            const float v = (py + .5f - vy) * uy - .5f;
            const int v0 = std::clamp(static_cast<int>(std::floor(v)), 0, bh - 1), v1 = std::min(v0 + 1, bh - 1);
            const float fv = std::clamp(v - v0, 0.f, 1.f);
            auto *row = pixels + static_cast<size_t>(py) * stride;
            for (int px = x0; px < x1; ++px) {
                const float u = (px + .5f - vx) * ux - .5f;
                const int u0 = std::clamp(static_cast<int>(std::floor(u)), 0, bw - 1), u1 = std::min(u0 + 1, bw - 1);
                const float fu = std::clamp(u - u0, 0.f, 1.f);
                const auto *p00 = slot->bitmap.buffer + v0 * pitch + u0 * 4, *p10 = p00 + (u1 - u0) * 4;
                const auto *p01 = slot->bitmap.buffer + v1 * pitch + u0 * 4, *p11 = p01 + (u1 - u0) * 4;
                float c[4];
                for (int k = 0; k < 4; ++k)
                    c[k] = (p00[k] * (1 - fu) + p10[k] * fu) * (1 - fv) + (p01[k] * (1 - fu) + p11[k] * fu) * fv;
                auto *d = row + static_cast<size_t>(px) * 4;
                const float inv = 1.f - c[3] / 255.f;
                d[0] = static_cast<uint8_t>(std::lround(c[2] + d[0] * inv));
                d[1] = static_cast<uint8_t>(std::lround(c[1] + d[1] * inv));
                d[2] = static_cast<uint8_t>(std::lround(c[0] + d[2] * inv));
                d[3] = static_cast<uint8_t>(std::lround(c[3] + d[3] * inv));
            }
        }
        return true;
    }
    void shape(dc_drawing_view &v, hb_buffer_t *buffer, uint32_t style, uint32_t start, uint32_t end,
               uint32_t context, uint32_t context_count) {
        Run run{start, end, style, (v.levels[start] & 1) != 0, {}};
        const auto &font = v.fonts[style];
        uint32_t at = start;
        while (at < end) {
            const auto face = text_faces[at];
            auto until = at + (codepoint(v.text, v.text_count, at) > 0xffff ? 2 : 1);
            while (until < end && text_faces[until] == face && v.levels[until] == v.levels[at])
                until += codepoint(v.text, v.text_count, until) > 0xffff ? 2 : 1;
            configure(face, font);
            hb_buffer_clear_contents(buffer);
            hb_buffer_add_utf16(buffer, v.text + context, context_count, at - context, until - at);
            hb_buffer_set_direction(buffer, v.levels[at] & 1 ? HB_DIRECTION_RTL : HB_DIRECTION_LTR);
            hb_buffer_set_language(buffer, hb_language_from_string(language.c_str(), -1));
            hb_buffer_guess_segment_properties(buffer);
            hb_shape(faces[face]->hb, buffer, nullptr, 0);
            unsigned count;
            const auto info = hb_buffer_get_glyph_infos(buffer, &count);
            const auto position = hb_buffer_get_glyph_positions(buffer, nullptr);
            for (unsigned g = 0; g < count; ++g) {
                const auto cluster = context + info[g].cluster;
                const float advance = position[g].x_advance / 65536.f;
                run.glyphs.push_back({info[g].codepoint, cluster, face, advance, position[g].x_offset / 65536.f, -position[g].y_offset / 65536.f});
                v.advances[cluster] += advance;
            }
            at = until;
        }
        // Letter spacing belongs to clusters, not continuation code units.
        for (size_t g = 0; g < run.glyphs.size(); ++g) {
            auto &glyph = run.glyphs[g];
            if ((v.breaks[glyph.cluster] >> 1) != 0)
                half_variant(glyph.face, codepoint(v.text, v.text_count, glyph.cluster), font);
            if (g + 1 == run.glyphs.size() || run.glyphs[g + 1].cluster != glyph.cluster) {
                glyph.advance += font.letter_spacing;
                v.advances[glyph.cluster] += font.letter_spacing;
            }
        }
        runs.push_back(std::move(run));
    }
    void measure(float pixel_scale) {
        DC_PROFILE("measure.total");
        require(!measured, "Portable measurement is single-use");
        require(std::isfinite(pixel_scale) && pixel_scale > 0, "Invalid measurement pixel scale");
        auto v = dc_drawing_plan_view(plan);
        std::fill(v.advances, v.advances + v.text_count, 0.f);
        std::fill(v.breaks, v.breaks + v.text_count, 0);
        std::fill(v.levels, v.levels + v.text_count, 0);
        std::vector<char> boundaries;
        text_faces.resize(v.text_count);
        index_families();
        // Every code point belongs to one request, so the request style decides
        // which CSS family list applies to it.
        std::vector<uint32_t> text_styles(v.text_count, 0);
        for (size_t q = 0; q < v.request_count; ++q) {
            const auto &request = v.requests[q];
            const size_t begin = std::min<size_t>(request.start, v.text_count);
            const size_t end = std::min<size_t>(static_cast<size_t>(request.start) + request.count, v.text_count);
            for (size_t j = begin; j < end; ++j) text_styles[j] = request.style;
        }

        for (size_t i = 0; i < v.context_count; ++i) {
            const auto &c = v.contexts[i];
            if (!c.count) continue;
            // Grapheme segmentation only matters for combining marks, ZWJ emoji and
            // variation/regional sequences. Japanese and English glossaries have none,
            // so their graphemes are code points and the libunibreak pass is skipped.
            bool simple = true;
            for (uint32_t j = c.start; j < c.start + c.count && simple;) {
                const auto cp = codepoint(v.text, v.text_count, j);
                simple = !(cp == 0x200d || (cp >= 0xfe00 && cp <= 0xfe0f) || (cp >= 0xe0100 && cp <= 0xe01ef) ||
                           (cp >= 0x1f1e6 && cp <= 0x1f1ff) || (cp >= 0x1f3fb && cp <= 0x1f3ff) ||
                           (cp >= 0x0300 && cp <= 0x036f) || (cp >= 0x0483 && cp <= 0x0489) ||
                           (cp >= 0x0591 && cp <= 0x05bd) || (cp >= 0x0610 && cp <= 0x061a) ||
                           (cp >= 0x064b && cp <= 0x065f) || (cp >= 0x0900 && cp <= 0x0e3a) ||
                           (cp >= 0x1ab0 && cp <= 0x1aff) || (cp >= 0x1dc0 && cp <= 0x1dff) ||
                           (cp >= 0x20d0 && cp <= 0x20ff) || (cp >= 0xfe20 && cp <= 0xfe2f));
                j += cp > 0xffff ? 2 : 1;
            }
            if (simple) {
                DC_PROFILE("measure.faces");
                for (uint32_t j = c.start; j < c.start + c.count;) {
                    const auto cp = codepoint(v.text, v.text_count, j);
                    const uint32_t width = cp > 0xffff ? 2 : 1;
                    std::fill(text_faces.begin() + j, text_faces.begin() + j + width,
                        face_for_style(cp, text_styles[j]));
                    j += width;
                }
            } else {
                boundaries.resize(c.count);
                DC_PROFILE("measure.graphemes");
                set_graphemebreaks_utf16(v.text+c.start, c.count, language.c_str(), boundaries.data());
                for (uint32_t from = 0; from < c.count;) {
                    auto until = from + 1;
                    while (until < c.count && boundaries[until-1] != GRAPHEMEBREAK_BREAK) ++until;
                    const auto face = cluster_face(v.text, c.start+from, c.start+until, text_styles[c.start+from]);
                    std::fill(text_faces.begin()+c.start+from, text_faces.begin()+c.start+until, face);
                    from = until;
                }
            }
            if (!(c.annotation & 1)) {
                boundaries.resize(c.count);
                DC_PROFILE("measure.linebreak");
                set_linebreaks_utf16(v.text + c.start, c.count, language.c_str(), boundaries.data());
                for (uint32_t j = 0; j < c.count; ++j) v.breaks[c.start + j] = boundaries[j] <= LINEBREAK_ALLOWBREAK;
                v.breaks[c.start + c.count - 1] = 1;
            }
            // ICU bidi setup is per paragraph and expensive; Japanese/English dictionaries
            // contain no RTL code points, and levels are already zero-filled above.
            bool rtl = false;
            for (uint32_t j = c.start; j < c.start + c.count && !rtl;) {
                const auto cp = codepoint(v.text, v.text_count, j);
                rtl = (cp >= 0x0590 && cp <= 0x08FF) || (cp >= 0xFB1D && cp <= 0xFDFF) ||
                      (cp >= 0xFE70 && cp <= 0xFEFF) || (cp >= 0x10800 && cp <= 0x10FFF) ||
                      (cp >= 0x1E800 && cp <= 0x1EFFF);
                j += cp > 0xffff ? 2 : 1;
            }
            if (rtl) {
                DC_PROFILE("measure.bidi");
                const SBCodepointSequence sequence{SBStringEncodingUTF16, v.text + c.start, c.count};
                auto algorithm = SBAlgorithmCreate(&sequence);
                require(algorithm != nullptr, "Cannot allocate bidi analysis");
                for (SBUInteger at = 0; at < c.count;) {
                    auto paragraph = SBAlgorithmCreateParagraph(algorithm, at, c.count - at, SBLevelDefaultLTR);
                    if (!paragraph) { SBAlgorithmRelease(algorithm); throw std::runtime_error("Cannot resolve bidi paragraph"); }
                    const auto length = SBParagraphGetLength(paragraph);
                    std::copy_n(SBParagraphGetLevelsPtr(paragraph), length, v.levels + c.start + at);
                    SBParagraphRelease(paragraph);
                    if (!length) break;
                    at += length;
                }
                SBAlgorithmRelease(algorithm);
            }
        }
        const auto facts = dc_drawing_font_facts(plan);
        DC_PROFILE("measure.facts");
        faces.front()->load();
        for (size_t i = 0; i < facts.font_count; ++i) {
            const auto &font = v.fonts[facts.styles[i]];
            const auto face = faces.front()->ft;
            faces[face_for(0x6f22)]->load();
            const auto em_face = faces[face_for(0x6f22)]->ft;
            const auto units = static_cast<float>(face->units_per_EM);
            const float ascent = -face->ascender * font.size / units, descent = -face->descender * font.size / units;
            auto os2 = static_cast<TT_OS2 *>(FT_Get_Sfnt_Table(em_face, ft_sfnt_os2));
            const float height = os2 ? os2->sTypoAscender - os2->sTypoDescender : 0;
            const float em_ascent = height > 0 ? os2->sTypoAscender * font.size / height : -ascent * font.size / (descent - ascent);
            const float em_descent = height > 0 ? -os2->sTypoDescender * font.size / height : descent * font.size / (descent - ascent);
            facts.metrics[i] = {ascent, descent, std::min(std::ceil(em_ascent * pixel_scale) / pixel_scale, -ascent),
                std::min(std::ceil(em_descent * pixel_scale) / pixel_scale, descent)};
        }
        for (size_t i = 0; i < facts.punctuation_count; ++i) {
            const auto cp = facts.punctuation[i * 2 + 1];
            const auto &font = v.fonts[facts.styles[facts.punctuation[i * 2]]];
            faces[face_for(cp)]->load();
            const auto face = faces[face_for(cp)]->ft;
            FT_Load_Char(face, cp, FT_LOAD_NO_SCALE | FT_LOAD_NO_HINTING);
            const auto &m = face->glyph->metrics;
            const float half = face->glyph->advance.x / 2.f;
            auto kind = m.width <= 0 ? 0 : m.horiBearingX + m.width <= half ? 2 : m.horiBearingX >= half ? 1 :
                m.width <= half && m.horiBearingX >= half / 2 ? 3 : 0;
            if ((cp == 0x2018 || cp == 0x201c) && kind != 1) kind = 4;
            if ((cp == 0x2019 || cp == 0x201d) && kind != 2) kind = 5;
            facts.punctuation_types[i] = kind;
            (void)font;
        }
        dc_error error{};
        {
            DC_PROFILE("measure.apply");
            if (dc_drawing_apply_font_facts(plan, &error)) throw std::runtime_error(error.message);
        }
        DC_PROFILE("measure.shape");
        auto buffer = hb_buffer_create();
        require(buffer != nullptr, "Cannot allocate shaping buffer");
        for (size_t i = 0; i < v.shaping_run_count; ++i) {
            const auto &g = v.shaping_runs[i];
            const auto &first = v.requests[g.first_request], &last = v.requests[g.first_request + g.request_count - 1];
            shape(v, buffer, first.style, first.start, last.start + last.count, first.context_start, first.context_count);
        }
        hb_buffer_destroy(buffer);
        std::sort(runs.begin(), runs.end(), [](const Run &a, const Run &b) { return a.start < b.start; });
        measured = true;
    }
    agg::path_storage &glyph_path(uint32_t face_index, uint32_t glyph, const dc_drawing_font &font) {
        const PathKey key{face_index, glyph, static_cast<uint32_t>(std::lround(font.size * 64)), (font.flags & 1) | (font.weight >= 600 ? 2 : 0)};
        auto found = paths.find(key);
        if (found != paths.end()) return found->second;
        faces[face_index]->load();
        auto face = faces[face_index]->ft;
        require(FT_Set_Char_Size(face, 0, std::lround(font.size * 64), 72, 72) == 0, "Cannot size font");
        require(FT_Load_Glyph(face, glyph, FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP) == 0, "Cannot load glyph outline");
        if (font.weight >= 600) FT_Outline_Embolden(&face->glyph->outline, std::lround(font.size * .025f * 64));
        if (font.flags & 1) {
            FT_Matrix shear{65536, 16384, 0, 65536};
            FT_Outline_Transform(&face->glyph->outline, &shear);
        }
        Outline result;
        FT_Outline_Funcs funcs{Outline::move, Outline::line, Outline::conic, Outline::cubic, 0, 0};
        require(FT_Outline_Decompose(&face->glyph->outline, &funcs, &result) == 0, "Cannot decompose glyph");
        if (result.contour) result.path.close_polygon();
        return paths.emplace(key, result.path).first->second;
    }
    void raster(uint32_t source, float scale, float sx, float sy, uint32_t width, uint32_t height, uint8_t *pixels, size_t stride,
                const uint32_t *text_color = nullptr) {
        require(measured && pixels && width && height && std::isfinite(scale) && scale > 0 &&
            stride >= static_cast<size_t>(width) * 4 && stride <= INT_MAX && height <= INT_MAX && width <= INT_MAX,
            "Invalid portable viewport");
        const auto v = dc_drawing_plan_view(plan);
        require(source < v.source_count, "Invalid portable source");
        agg::rendering_buffer buffer(pixels, width, height, static_cast<int>(stride));
        PixelFormat format(buffer);
        Renderer renderer(format);
        renderer.clear(agg::rgba8(0, 0, 0, 0));
        agg::rasterizer_scanline_aa<> rasterizer;
        rasterizer.clip_box(0, 0, width, height);
        agg::scanline_u8 scanline;
        auto fill = [&](auto &path, uint32_t value, bool even_odd = false) {
            agg::trans_affine transform(scale, 0, 0, scale, -sx * scale, -sy * scale);
            agg::conv_transform<std::remove_reference_t<decltype(path)>> transformed(path, transform);
            rasterizer.reset(); rasterizer.filling_rule(even_odd ? agg::fill_even_odd : agg::fill_non_zero);
            rasterizer.add_path(transformed);
            agg::render_scanlines_aa_solid(rasterizer, scanline, renderer, color(value));
        };
        const auto &s = v.sources[source];
        for (uint32_t i = s.first_command; i < s.first_command + s.command_count; ++i) {
            const auto &c = v.commands[i];
            if (c.kind == 2 || c.y >= sy + height / scale || c.y + c.height <= sy || c.x >= sx + width / scale || c.x + c.width <= sx) continue;
            if (c.kind == 0) {
                const auto &font = v.fonts[c.style];
                float pen = 0;
                const auto first = std::lower_bound(runs.begin(), runs.end(), c.start,
                    [](const Run &run, uint32_t offset) { return run.end <= offset; });
                for (auto it = first; it != runs.end() && it->start < c.start + c.count; ++it) {
                    const auto &run = *it;
                    if (c.start >= run.end || c.start + c.count <= run.start) continue;
                    for (const auto &g : run.glyphs) {
                        if (g.cluster < c.start || g.cluster >= c.start + c.count) continue;
                        const auto glyph = c.flags & 16 ? half_variant(g.face,
                            codepoint(v.text, v.text_count, g.cluster), font) : GlyphVariant{g.id, g.dx, g.dy};
                        if (blit(g.face, glyph.id, font, c.x + pen + glyph.dx, c.baseline + glyph.dy,
                            scale, sx, sy, width, height, pixels, stride)) {
                            pen += g.advance;
                            continue;
                        }
                        auto &path = glyph_path(g.face, glyph.id, font);
                        agg::conv_curve<agg::path_storage> curve(path);
                        agg::trans_affine position;
                        position.translate(c.x + pen + glyph.dx, c.baseline + glyph.dy);
                        agg::conv_transform<decltype(curve)> placed(curve, position);
                        fill(placed, text_color ? *text_color : c.color);
                        pen += g.advance;
                    }
                }
                if (font.flags & 6) {
                    const float y = c.baseline + (font.flags & 2 ? font.size * .1f : -font.size * .3f);
                    agg::rounded_rect line(c.x, y, c.x + c.width, y + std::max(.5f, font.size / 16), 0);
                    fill(line, text_color ? *text_color : c.color);
                }
            } else if (c.kind == 1) {
                agg::rounded_rect outer(c.x, c.y, c.x + c.width, c.y + c.height, c.radius);
                outer.normalize_radius();
                if (c.color >> 24) fill(outer, c.color);
                if (c.stroke > 0 && c.border >> 24) {
                    const auto edge = c.flags & 0xf00;
                    const float l = !edge || edge == 0x800 ? c.stroke : 0, t = !edge || edge == 0x100 ? c.stroke : 0;
                    const float r = !edge || edge == 0x200 ? c.stroke : 0, b = !edge || edge == 0x400 ? c.stroke : 0;
                    agg::path_storage border;
                    border.concat_path(outer);
                    if (c.width > l + r && c.height > t + b) {
                        agg::rounded_rect inner(c.x + l, c.y + t, c.x + c.width - r, c.y + c.height - b, 0);
                        inner.radius(std::max(0.f, c.radius - l), std::max(0.f, c.radius - t),
                            std::max(0.f, c.radius - r), std::max(0.f, c.radius - t),
                            std::max(0.f, c.radius - r), std::max(0.f, c.radius - b),
                            std::max(0.f, c.radius - l), std::max(0.f, c.radius - b));
                        inner.normalize_radius(); border.concat_path(inner);
                    }
                    fill(border, c.border, true);
                }
            } else if (c.kind == 3) {
                agg::path_storage shape;
                if (c.flags & 1) {
                    const auto radius = std::min(c.width, c.height) / 2;
                    agg::ellipse circle(c.x + c.width / 2, c.y + c.height / 2, radius, radius);
                    shape.concat_path(circle);
                } else {
                    shape.move_to(c.x, c.y); shape.line_to(c.x + c.width, c.y);
                    shape.line_to(c.x + c.width, c.y + c.height); shape.line_to(c.x, c.y + c.height); shape.close_polygon();
                }
                agg::trans_affine geometry(scale, 0, 0, scale, -sx * scale, -sy * scale);
                agg::conv_transform<agg::path_storage> transformed(shape, geometry);
                rasterizer.reset(); rasterizer.filling_rule(agg::fill_non_zero); rasterizer.add_path(transformed);
                agg::pod_auto_array<agg::rgba8, 256> colors;
                for (unsigned n = 0; n < 256; ++n) {
                    const float mix = std::clamp((n / 255.f - c.stroke) / std::max(.0001f, c.radius - c.stroke), 0.f, 1.f);
                    auto a = color(c.color), b = color(c.border);
                    colors[n] = agg::rgba8(std::lround(a.r + (b.r-a.r)*mix), std::lround(a.g + (b.g-a.g)*mix),
                        std::lround(a.b + (b.b-a.b)*mix), std::lround(a.a + (b.a-a.a)*mix));
                }
                agg::trans_affine transform;
                transform.translate(-(c.x + c.width / 2 - sx) * scale, -(c.y + c.height / 2 - sy) * scale);
                transform.scale(256 / (c.width * .70710677f * scale), 256 / (c.height * .70710677f * scale));
                agg::span_interpolator_linear<> interpolator(transform);
                agg::gradient_radial gradient;
                agg::span_allocator<agg::rgba8> allocator;
                agg::span_gradient<agg::rgba8, decltype(interpolator), agg::gradient_radial,
                    decltype(colors)> span(interpolator, gradient, colors, 0., 256.);
                agg::render_scanlines_aa(rasterizer, scanline, renderer, allocator, span);
            }
        }
    }
};

namespace {
template<class F> int guarded(dc_error *error, F &&fn) {
    try { fn(); return 0; }
    catch (const std::exception &e) { if (error) std::snprintf(error->message, sizeof(error->message), "%s", e.what()); return 1; }
    catch (...) { if (error) std::snprintf(error->message, sizeof(error->message), "Portable renderer failed"); return 1; }
}
}
int dc_portable_create(dc_drawing_plan *plan, const dc_font_file *files, size_t count, dc_portable_document **out, dc_error *error) {
    return dc_portable_create_language(plan, files, count, "ja", out, error);
}
int dc_portable_create_language(dc_drawing_plan *plan, const dc_font_file *files, size_t count, const char *language, dc_portable_document **out, dc_error *error) {
    if (out) *out = nullptr;
    return guarded(error, [&] { require(out != nullptr, "Missing portable output"); *out = new dc_portable_document(plan, files, count, language); });
}
int dc_portable_measure(dc_portable_document *doc, float pixel_scale, dc_error *error) {
    return guarded(error, [&] { require(doc != nullptr, "Closed portable document"); doc->measure(pixel_scale); });
}
int dc_portable_raster(dc_portable_document *doc, uint32_t source, float scale, float sx, float sy,
    uint32_t width, uint32_t height, uint8_t *pixels, size_t stride, dc_error *error) {
    return guarded(error, [&] { DC_PROFILE("raster.total"); require(doc != nullptr, "Closed portable document"); doc->raster(source, scale, sx, sy, width, height, pixels, stride); });
}
void dc_portable_destroy(dc_portable_document *doc) { delete doc; }
int dc_portable_raster_tinted(dc_portable_document *doc, uint32_t source, float scale, float sx, float sy,
    uint32_t width, uint32_t height, uint8_t *pixels, size_t stride, uint32_t text_color, int override_color, dc_error *error) {
    return guarded(error, [&] {
        DC_PROFILE("raster.total");
        require(doc != nullptr, "Closed portable document");
        doc->raster(source, scale, sx, sy, width, height, pixels, stride, override_color ? &text_color : nullptr);
    });
}
