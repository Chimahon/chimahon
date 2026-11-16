#include "dictionary_core.h"
#include "document_layout.h"
#include "drawing_plan.h"
#include "styled_document.h"
#include "portable_renderer.h"
#include "profile.hpp"
#include "android_dictionary_fonts.h"
#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif
#include <android/bitmap.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <cstdio>
#include <cstring>
#include <jni.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
thread_local bool profiling = false;
int profile_mode() {
#if defined(__ANDROID__)
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get("debug.chima.profile", value) <= 0)
        return 0;
    if (value[0] == '2')
        return 2;
    if (value[0] == '1')
        return 1;
#endif
    return 0;
}
bool profiling_enabled() { return profile_mode() != 0; }
template <class T, auto Destroy> using Owned = std::unique_ptr<T, decltype(Destroy)>;
struct Context {
    Owned<dc_stylesheets, dc_stylesheets_destroy> sheets{nullptr, dc_stylesheets_destroy};
    dc_compile_options options;
    std::string language = "ja";
};
struct Drawing {
    Owned<dc_prepared_document, dc_prepared_document_destroy> prepared{nullptr, dc_prepared_document_destroy};
    Owned<dc_drawing_plan, dc_drawing_plan_destroy> plan{nullptr, dc_drawing_plan_destroy};
    Owned<dc_portable_document, dc_portable_destroy> portable{nullptr, dc_portable_destroy};
    std::string language = "ja";
    float parse_ms = 0, style_ms = 0, begin_ms = 0;
};
void failure(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck())
        return;
    auto type = env->FindClass("java/lang/IllegalStateException");
    if (type) {
        env->ThrowNew(type, message);
        env->DeleteLocalRef(type);
    }
}
std::string utf8(JNIEnv *, jstring);
template <class T> T &object(jlong handle) {
    if (!handle)
        throw std::invalid_argument("Closed native handle");
    return *reinterpret_cast<T *>(static_cast<uintptr_t>(handle));
}
} // namespace
extern "C" JNIEXPORT jlong JNICALL Java_chimahon_renderer_DictionaryCore_openBatch(JNIEnv *env, jobject,
                                                                                   jobjectArray css, jfloat font,
                                                                                   jboolean dark, jboolean expanded,
                                                                                   jstring language) {
    try {
        if (!css)
            throw std::invalid_argument("Null stylesheets");
        const auto n = env->GetArrayLength(css);
        std::vector<std::string> strings;
        strings.reserve(n);
        for (jsize i = 0; i < n; ++i) {
            auto s = static_cast<jstring>(env->GetObjectArrayElement(css, i));
            if (env->ExceptionCheck())
                return 0;
            try {
                strings.push_back(utf8(env, s));
            } catch (...) {
                env->DeleteLocalRef(s);
                throw;
            }
            env->DeleteLocalRef(s);
        }
        std::vector<dc_css_source> sources;
        for (const auto &s : strings)
            sources.push_back({s.data(), s.size()});
        auto result = std::make_unique<Context>();
        result->language = language ? utf8(env, language) : "ja";
        if (result->language.empty()) result->language = "ja";
        // Enumerate the system font catalog while the popup is still composing; the
        // first portable batch would otherwise pay it on the plan's critical path.
        dictionary_system_fonts(result->language.c_str());
        // Match the unchanged Android WebView's default minimum text size.
        result->options = {font, dark != 0, expanded != 0,8.f};
        dc_stylesheets *sheets = nullptr;
        dc_error error{};
        if (dc_stylesheets_create(sources.data(), sources.size(), &sheets, &error))
            throw std::runtime_error(error.message);
        result->sheets.reset(sheets);
        return reinterpret_cast<uintptr_t>(result.release());
    } catch (const std::exception &e) {
        failure(env, e.what());
    } catch (...) {
        failure(env, "Native batch context failed");
    }
    return 0;
}
// Signature-qualified linkage rejects older hosts instead of misreading their batch arguments.
extern "C" JNIEXPORT jlong JNICALL Java_chimahon_renderer_DictionaryCore_prepareDrawing__J_3Ljava_lang_String_2_3Ljava_nio_ByteBuffer_2_3I_3FII(
    JNIEnv *env, jobject, jlong context, jobjectArray input, jobjectArray typed, jintArray indices, jfloatArray widths, jint foreground,
    jint border) {
    try {
        if (!input || !typed || !indices || !widths)
            throw std::invalid_argument("Null drawing batch");
        auto &c = object<Context>(context);
        static std::atomic<int64_t> next_profile{1};
        profiling = profiling_enabled();
        if (profiling) dc_profile::begin(next_profile.fetch_add(1), profile_mode() == 2);
        const auto count = env->GetArrayLength(input);
        if (env->GetArrayLength(typed) != count || env->GetArrayLength(indices) != count || env->GetArrayLength(widths) != count)
            throw std::invalid_argument("Drawing batch lengths differ");
        std::vector<std::string> strings;
        strings.reserve(count);
        for (jsize i = 0; i < count; ++i) {
            auto s = static_cast<jstring>(env->GetObjectArrayElement(input, i));
            if (env->ExceptionCheck())
                return 0;
            try {
                strings.push_back(utf8(env, s));
            } catch (...) {
                env->DeleteLocalRef(s);
                throw;
            }
            env->DeleteLocalRef(s);
        }
        std::vector<dc_source> sources;
        for (const auto &s : strings)
            sources.push_back({s.data(), s.size()});
        std::vector<dc_typed_source> tapes(count);
        std::vector<jobject> buffers;
        struct References {
            JNIEnv *env; std::vector<jobject> &buffers;
            ~References() { for (auto buffer : buffers) env->DeleteLocalRef(buffer); }
        } references{env, buffers};
        for (jsize i = 0; i < count; ++i) {
            auto buffer = env->GetObjectArrayElement(typed, i);
            if (env->ExceptionCheck()) return 0;
            if (!buffer) continue;
            buffers.push_back(buffer);
            const auto size = env->GetDirectBufferCapacity(buffer);
            auto *data = env->GetDirectBufferAddress(buffer);
            if (!data || size < 8) throw std::invalid_argument("Invalid typed drawing source");
            tapes[i] = {data, static_cast<size_t>(size)};
        }
        std::vector<uint32_t> mapping(count);
        std::vector<float> sizes(count);
        env->GetIntArrayRegion(indices, 0, count, reinterpret_cast<jint *>(mapping.data()));
        env->GetFloatArrayRegion(widths, 0, count, sizes.data());
        if (env->ExceptionCheck())
            return 0;
        const auto clock = [] { return std::chrono::steady_clock::now(); };
        const auto start = clock();
        dc_document *parsed = nullptr;
        dc_error error{};
        if (dc_prepare_mixed(sources.data(), tapes.data(), sources.size(), &parsed, &error))
            throw std::runtime_error(error.message);
        const auto parsed_at = clock();
        Owned<dc_document, dc_document_destroy> raw(parsed, dc_document_destroy);
        auto result = std::make_unique<Drawing>();
        result->language = c.language;
        dc_prepared_document *prepared = nullptr;
        if (dc_compile_styled_configured(parsed, c.sheets.get(), mapping.data(), mapping.size(), &c.options, &prepared,
                                         &error))
            throw std::runtime_error(error.message);
        const auto styled_at = clock();
        result->prepared.reset(prepared);
        dc_drawing_plan *plan = nullptr;
        if (dc_drawing_begin(prepared, sizes.data(), sizes.size(), foreground, border, &plan, &error))
            throw std::runtime_error(error.message);
        const auto begin_at = clock();
        result->parse_ms = std::chrono::duration<float, std::milli>(parsed_at - start).count();
        result->style_ms = std::chrono::duration<float, std::milli>(styled_at - parsed_at).count();
        result->begin_ms = std::chrono::duration<float, std::milli>(begin_at - styled_at).count();
        result->plan.reset(plan);
        return reinterpret_cast<uintptr_t>(result.release());
    } catch (const std::exception &e) {
        failure(env, e.what());
    } catch (...) {
        failure(env, "Native drawing preparation failed");
    }
    return 0;
}
extern "C" JNIEXPORT jintArray JNICALL Java_chimahon_renderer_DictionaryCore_drawingCounts(JNIEnv *env, jobject,
                                                                                           jlong handle) {
    try {
        auto &d = object<Drawing>(handle);
        const auto v = dc_drawing_plan_view(d.plan.get());
        const auto p = dc_prepared_document_view(d.prepared.get());
        const auto facts = dc_drawing_font_facts(d.plan.get());
        const size_t sizes[]{
            v.font_count,    v.names_bytes,     v.text_count,   v.request_count, v.context_count,
            v.command_count, v.hit_count,       v.source_count, p.image_count,   p.property_count,
            p.text_bytes,    p.paragraph_count, p.ruby_count,   p.table_count,   static_cast<size_t>(v.lines), v.shaping_run_count,
            facts.font_count, facts.punctuation_count};
        jint values[18];
        for (size_t i = 0; i < 18; ++i) {
            if (sizes[i] > INT_MAX)
                throw std::length_error("Drawing packet limit");
            values[i] = static_cast<jint>(sizes[i]);
        }
        auto result = env->NewIntArray(18);
        if (result)
            env->SetIntArrayRegion(result, 0, 18, values);
        return result;
    } catch (const std::exception &e) {
        failure(env, e.what());
    } catch (...) {
        failure(env, "Drawing counts failed");
    }
    return nullptr;
}
extern "C" JNIEXPORT jobject JNICALL Java_chimahon_renderer_DictionaryCore_drawingSection(JNIEnv *env, jobject,
                                                                                          jlong handle, jint section) {
    try {
        auto &d = object<Drawing>(handle);
        const auto v = dc_drawing_plan_view(d.plan.get());
        const auto p = dc_prepared_document_view(d.prepared.get());
        const void *data = nullptr;
        size_t size = 0;
        const auto facts = dc_drawing_font_facts(d.plan.get());
        switch (section) {
        case 0:
            data = v.fonts;
            size = v.font_count * sizeof(dc_drawing_font);
            break;
        case 1:
            data = v.names;
            size = v.names_bytes;
            break;
        case 2:
            data = v.text;
            size = v.text_count * 2;
            break;
        case 3:
            data = v.requests;
            size = v.request_count * sizeof(dc_measure_request);
            break;
        case 4:
            data = v.contexts;
            size = v.context_count * sizeof(dc_measure_context);
            break;
        case 5:
            data = v.metrics;
            size = v.request_count * sizeof(dc_run_metrics);
            break;
        case 6:
            data = v.advances;
            size = v.text_count * sizeof(float);
            break;
        case 7:
            data = v.breaks;
            size = v.text_count;
            break;
        case 8:
            data = v.levels;
            size = v.text_count;
            break;
        case 9:
            data = v.commands;
            size = v.command_count * sizeof(dc_draw_command);
            break;
        case 10:
            data = v.hits;
            size = v.hit_count * sizeof(dc_drawing_hit);
            break;
        case 11:
            data = v.sources;
            size = v.source_count * sizeof(dc_drawing_source);
            break;
        case 12:
            data = p.text;
            size = p.text_bytes;
            break;
        case 13:
            data = p.properties;
            size = p.property_count * sizeof(dc_style_property);
            break;
        case 14:
            data = p.images;
            size = p.image_count * sizeof(dc_image_input);
            break;
        case 15:
            data = v.shaping_runs;
            size = v.shaping_run_count * sizeof(dc_shaping_run);
            break;
        case 16: data = facts.styles; size = facts.font_count * sizeof(uint32_t); break;
        case 17: data = facts.style_fonts; size = v.font_count * sizeof(uint32_t); break;
        case 18: data = facts.metrics; size = facts.font_count * sizeof(dc_run_metrics); break;
        case 19: data = facts.punctuation; size = facts.punctuation_count * sizeof(uint32_t) * 2; break;
        case 20: data = facts.punctuation_types; size = facts.punctuation_count; break;
        default:
            throw std::invalid_argument("Unknown drawing section");
        }
        static char empty = 0;
        return env->NewDirectByteBuffer(const_cast<void *>(data ? data : &empty), size);
    } catch (const std::exception &e) {
        failure(env, e.what());
    } catch (...) {
        failure(env, "Drawing buffer failed");
    }
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_chimahon_renderer_DictionaryCore_finishDrawing(JNIEnv *env, jobject,
                                                                                      jlong handle) {
    try {
        auto &d = object<Drawing>(handle);
        dc_error error{};
        if (!d.portable && dc_drawing_apply_font_facts(d.plan.get(), &error))
            throw std::runtime_error(error.message);
        if (dc_drawing_finish(d.plan.get(), &error))
            throw std::runtime_error(error.message);
    } catch (const std::exception &e) {
        failure(env, e.what());
    } catch (...) {
        failure(env, "Drawing layout failed");
    }
    if (profiling) {
        dc_profile::end();
        profiling = false;
    }
}
extern "C" JNIEXPORT void JNICALL Java_chimahon_renderer_DictionaryCore_measurePortable(
    JNIEnv *env, jobject, jlong handle, jfloat scale, jobjectArray paths, jintArray indices) {
    try {
        auto &drawing = object<Drawing>(handle);
        if (drawing.portable) throw std::invalid_argument("Document already measured");
        const auto count = paths ? env->GetArrayLength(paths) : 0;
        if (!indices || env->GetArrayLength(indices) != count) throw std::invalid_argument("Font file indices differ");
        std::vector<std::string> names;
        std::vector<jint> collections(count);
        if (count) env->GetIntArrayRegion(indices, 0, count, collections.data());
        if (env->ExceptionCheck()) return;
        for (jsize i = 0; i < count; ++i) {
            auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
            if (env->ExceptionCheck()) return;
            try { names.push_back(utf8(env, path)); }
            catch (...) { env->DeleteLocalRef(path); throw; }
            env->DeleteLocalRef(path);
        }
        std::vector<dc_font_file> files;
        for (jsize i = 0; i < count; ++i) files.push_back({names[i].c_str(), static_cast<uint32_t>(collections[i])});
        // The selected app font comes first; system faces fill in missing glyphs.
        for (const auto &system : dictionary_system_fonts(drawing.language.c_str())) {
            const bool present = std::any_of(files.begin(), files.end(), [&](const dc_font_file &file) {
                return file.collection_index == system.collection_index && std::strcmp(file.path, system.path) == 0;
            });
            if (!present) files.push_back(system);
        }
        const auto &fonts = files;
        dc_portable_document *document = nullptr;
        dc_error error{};
        if (dc_portable_create_language(drawing.plan.get(), fonts.data(), fonts.size(), drawing.language.c_str(), &document, &error))
            throw std::runtime_error(error.message);
        drawing.portable.reset(document);
        if (dc_portable_measure(document, scale, &error)) throw std::runtime_error(error.message);
    } catch (const std::exception &error) { failure(env, error.what()); }
      catch (...) { failure(env, "Portable font preparation failed"); }
}
extern "C" JNIEXPORT void JNICALL Java_chimahon_renderer_DictionaryCore_rasterPortable(
    JNIEnv *env, jobject, jlong handle, jint source, jfloat scale, jfloat left, jfloat top,
    jint width, jint height, jobject bitmap, jint text_color, jboolean override_color) {
    try {
        auto &drawing = object<Drawing>(handle);
        AndroidBitmapInfo info{};
        if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || width <= 0 || height <= 0 ||
            static_cast<uint32_t>(width) > info.width || static_cast<uint32_t>(height) > info.height)
            throw std::invalid_argument("Invalid portable raster bitmap");
        void *pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot lock portable bitmap");
        struct Unlock { JNIEnv *env; jobject bitmap; ~Unlock() { AndroidBitmap_unlockPixels(env, bitmap); } } unlock{env, bitmap};
        dc_error error{};
        if (dc_portable_raster_tinted(drawing.portable.get(), source, scale, left, top, width, height,
            static_cast<uint8_t *>(pixels), info.stride, text_color, override_color, &error))
            throw std::runtime_error(error.message);
    } catch (const std::exception &error) { failure(env, error.what()); }
      catch (...) { failure(env, "Portable raster failed"); }
}
extern "C" JNIEXPORT void JNICALL Java_chimahon_renderer_DictionaryCore_closeDrawing(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Drawing *>(static_cast<uintptr_t>(handle));
}
namespace {
std::string utf8(JNIEnv *env, jstring input) {
    if (!input)
        throw std::invalid_argument("Null UTF-16 input");
    const auto length = env->GetStringLength(input);
    const auto *chars = env->GetStringChars(input, nullptr);
    if (!chars)
        throw std::bad_alloc();
    struct Release {
        JNIEnv *env;
        jstring string;
        const jchar *data;
        ~Release() { env->ReleaseStringChars(string, data); }
    } release{env, input, chars};
    std::string result;
    result.reserve(length);
    for (jsize i = 0; i < length; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xd800 && c <= 0xdbff) {
            if (i + 1 >= length || chars[i + 1] < 0xdc00 || chars[i + 1] > 0xdfff)
                throw std::invalid_argument("Unpaired UTF-16 surrogate");
            c = 0x10000 + ((c - 0xd800) << 10) + (chars[++i] - 0xdc00);
        } else if (c >= 0xdc00 && c <= 0xdfff)
            throw std::invalid_argument("Unpaired UTF-16 surrogate");
        if (c < 0x80)
            result += static_cast<char>(c);
        else if (c < 0x800) {
            result += static_cast<char>(0xc0 | (c >> 6));
            result += static_cast<char>(0x80 | (c & 63));
        } else if (c < 0x10000) {
            result += static_cast<char>(0xe0 | (c >> 12));
            result += static_cast<char>(0x80 | ((c >> 6) & 63));
            result += static_cast<char>(0x80 | (c & 63));
        } else {
            result += static_cast<char>(0xf0 | (c >> 18));
            result += static_cast<char>(0x80 | ((c >> 12) & 63));
            result += static_cast<char>(0x80 | ((c >> 6) & 63));
            result += static_cast<char>(0x80 | (c & 63));
        }
    }
    return result;
}
} // namespace
extern "C" JNIEXPORT void JNICALL Java_chimahon_renderer_DictionaryCore_closeContext(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Context *>(static_cast<uintptr_t>(handle));
}
extern "C" JNIEXPORT jfloatArray JNICALL Java_chimahon_renderer_DictionaryCore_placeCard(JNIEnv *env, jobject, jfloatArray input) {
    try {
        if (!input || env->GetArrayLength(input) % 6 != 0) throw std::invalid_argument("Invalid card parts");
        const auto count = env->GetArrayLength(input) / 6;
        std::vector<float> parts(count * 6), rectangles(count * 4 + 1);
        env->GetFloatArrayRegion(input, 0, count * 6, parts.data());
        if (env->ExceptionCheck()) return nullptr;
        rectangles.back() = dc_card_place(parts.data(), count, rectangles.data());
        auto result = env->NewFloatArray(static_cast<jsize>(rectangles.size()));
        if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(rectangles.size()), rectangles.data());
        return result;
    } catch (const std::exception &e) { failure(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL Java_chimahon_renderer_DictionaryCore_lookupWordAt(JNIEnv *env, jobject,
                                                                                        jstring text, jint offset) {
    try {
        if (!text) return nullptr;
        const jsize length = env->GetStringLength(text);
        const jchar *chars = env->GetStringChars(text, nullptr);
        if (!chars) return nullptr;
        const uint16_t *word = nullptr;
        size_t word_length = 0;
        const int found = dc_lookup_word_at(reinterpret_cast<const uint16_t *>(chars), length, offset,
                                            &word, &word_length);
        jstring result = found ? env->NewString(reinterpret_cast<const jchar *>(word), word_length) : nullptr;
        env->ReleaseStringChars(text, chars);
        return result;
    } catch (const std::exception &e) { failure(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_chimahon_renderer_DictionaryCore_splitMorae(JNIEnv *env, jobject,
                                                                                           jstring text) {
    try {
        if (!text) return nullptr;
        const jsize length = env->GetStringLength(text);
        const jchar *chars = env->GetStringChars(text, nullptr);
        if (!chars) return nullptr;
        dc_text_slice *morae = nullptr;
        size_t count = 0;
        if (dc_split_morae(reinterpret_cast<const uint16_t *>(chars), length, &morae, &count)) {
            env->ReleaseStringChars(text, chars);
            throw std::runtime_error("Mora split failed");
        }
        jclass string_class = env->FindClass("java/lang/String");
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(count), string_class, nullptr);
        if (result) {
            for (size_t i = 0; i < count && !env->ExceptionCheck(); ++i) {
                jstring value = env->NewString(
                    reinterpret_cast<const jchar *>(chars) + morae[i].offset,
                    static_cast<jsize>(morae[i].length));
                env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
                env->DeleteLocalRef(value);
            }
        }
        dc_text_slices_destroy(morae);
        env->ReleaseStringChars(text, chars);
        return result;
    } catch (const std::exception &e) { failure(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT jbooleanArray JNICALL Java_chimahon_renderer_DictionaryCore_moraHighFlags(JNIEnv *env, jobject,
                                                                                              jstring reading, jint accent) {
    try {
        if (!reading) return nullptr;
        const jsize length = env->GetStringLength(reading);
        const jchar *chars = env->GetStringChars(reading, nullptr);
        if (!chars) return nullptr;
        dc_text_slice *morae = nullptr;
        size_t count = 0;
        if (dc_split_morae(reinterpret_cast<const uint16_t *>(chars), length, &morae, &count)) {
            env->ReleaseStringChars(reading, chars);
            throw std::runtime_error("Mora split failed");
        }
        std::vector<jboolean> flags(count + 1);
        for (size_t i = 0; i <= count; ++i)
            flags[i] = dc_mora_high(i, accent) ? JNI_TRUE : JNI_FALSE;
        dc_text_slices_destroy(morae);
        env->ReleaseStringChars(reading, chars);
        auto result = env->NewBooleanArray(static_cast<jsize>(flags.size()));
        if (result) env->SetBooleanArrayRegion(result, 0, static_cast<jsize>(flags.size()), flags.data());
        return result;
    } catch (const std::exception &e) { failure(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL Java_chimahon_renderer_DictionaryCore_headwordTapeSize(JNIEnv *env, jobject,
                                                                                          jstring expression, jstring reading,
                                                                                          jstring classes) {
    try {
        if (!expression || !classes) throw std::invalid_argument("Null headword");
        const jsize expression_length = env->GetStringLength(expression);
        const jsize reading_length = reading ? env->GetStringLength(reading) : 0;
        const jsize classes_length = env->GetStringLength(classes);
        const jchar *expression_chars = env->GetStringChars(expression, nullptr);
        const jchar *reading_chars = reading ? env->GetStringChars(reading, nullptr) : nullptr;
        const jchar *class_chars = env->GetStringChars(classes, nullptr);
        if (!expression_chars || !class_chars || (reading && !reading_chars)) {
            if (expression_chars) env->ReleaseStringChars(expression, expression_chars);
            if (reading_chars) env->ReleaseStringChars(reading, reading_chars);
            if (class_chars) env->ReleaseStringChars(classes, class_chars);
            throw std::invalid_argument("Headword chars");
        }
        size_t bytes = 0;
        dc_error error{};
        const int status = dc_headword_tape_size(
            reinterpret_cast<const uint16_t *>(expression_chars), expression_length,
            reinterpret_cast<const uint16_t *>(reading_chars), reading_length,
            reinterpret_cast<const uint16_t *>(class_chars), classes_length, &bytes, &error);
        env->ReleaseStringChars(expression, expression_chars);
        if (reading_chars) env->ReleaseStringChars(reading, reading_chars);
        env->ReleaseStringChars(classes, class_chars);
        if (status) throw std::runtime_error(error.message);
        if (bytes > INT_MAX) throw std::length_error("Headword tape too large");
        return static_cast<jint>(bytes);
    } catch (const std::exception &e) { failure(env, e.what()); }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL Java_chimahon_renderer_DictionaryCore_headwordTapeInto(JNIEnv *env, jobject,
                                                                                          jobject buffer, jstring expression,
                                                                                          jstring reading, jstring classes) {
    try {
        if (!buffer || !expression || !classes) throw std::invalid_argument("Null headword tape");
        auto *out = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
        const auto capacity = env->GetDirectBufferCapacity(buffer);
        if (!out || capacity < 0) throw std::invalid_argument("Headword tape needs a direct buffer");
        const jsize expression_length = env->GetStringLength(expression);
        const jsize reading_length = reading ? env->GetStringLength(reading) : 0;
        const jsize classes_length = env->GetStringLength(classes);
        const jchar *expression_chars = env->GetStringChars(expression, nullptr);
        const jchar *reading_chars = reading ? env->GetStringChars(reading, nullptr) : nullptr;
        const jchar *class_chars = env->GetStringChars(classes, nullptr);
        if (!expression_chars || !class_chars || (reading && !reading_chars)) {
            if (expression_chars) env->ReleaseStringChars(expression, expression_chars);
            if (reading_chars) env->ReleaseStringChars(reading, reading_chars);
            if (class_chars) env->ReleaseStringChars(classes, class_chars);
            throw std::invalid_argument("Headword chars");
        }
        size_t written = 0;
        dc_error error{};
        const int status = dc_headword_tape_into(
            out, static_cast<size_t>(capacity),
            reinterpret_cast<const uint16_t *>(expression_chars), expression_length,
            reinterpret_cast<const uint16_t *>(reading_chars), reading_length,
            reinterpret_cast<const uint16_t *>(class_chars), classes_length, &written, &error);
        env->ReleaseStringChars(expression, expression_chars);
        if (reading_chars) env->ReleaseStringChars(reading, reading_chars);
        env->ReleaseStringChars(classes, class_chars);
        if (status) throw std::runtime_error(error.message);
        return static_cast<jint>(written);
    } catch (const std::exception &e) { failure(env, e.what()); }
    return 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_chimahon_renderer_DictionaryCore_hitAt(JNIEnv *env, jobject,
                                                                                       jlong handle, jint source,
                                                                                       jfloat x, jfloat y) {
    try {
        auto &d = object<Drawing>(handle);
        if (!d.plan) throw std::invalid_argument("Missing drawing plan");
        dc_hit_lookup_result hit{};
        dc_error error{};
        const int status = dc_hit_lookup(d.plan.get(), static_cast<uint32_t>(source), x, y, &hit, &error);
        if (status < 0) throw std::runtime_error(error.message);
        if (status == 0) return nullptr;
        jclass string_class = env->FindClass("java/lang/String");
        jobjectArray result = env->NewObjectArray(6, string_class, nullptr);
        if (result) {
            static const jchar empty_char = 0;
            jstring word = env->NewString(hit.word ? reinterpret_cast<const jchar *>(hit.word) : &empty_char,
                                          static_cast<jsize>(hit.word_length));
            jstring sentence = env->NewString(hit.sentence ? reinterpret_cast<const jchar *>(hit.sentence) : &empty_char,
                                              static_cast<jsize>(hit.sentence_length));
            jstring values[] = {
                word,
                sentence,
                env->NewStringUTF(std::to_string(hit.offset).c_str()),
                env->NewStringUTF(std::to_string(hit.text_offset).c_str()),
                env->NewStringUTF(std::to_string(hit.sentence_offset).c_str()),
                env->NewStringUTF(std::to_string(hit.character).c_str()),
            };
            for (int i = 0; i < 6 && !env->ExceptionCheck(); ++i) {
                env->SetObjectArrayElement(result, i, values[i]);
                env->DeleteLocalRef(values[i]);
            }
        }
        dc_hit_lookup_result_destroy(&hit);
        return result;
    } catch (const std::exception &e) { failure(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_chimahon_renderer_DictionaryCore_selectionRects(
    JNIEnv *env, jobject, jlong handle, jint source, jint offset, jint code_points) {
    try {
        auto &d = object<Drawing>(handle);
        if (!d.plan) throw std::invalid_argument("Missing drawing plan");
        float *rects = nullptr;
        size_t count = 0;
        const int status = dc_selection_rects(d.plan.get(), static_cast<uint32_t>(source),
                                              static_cast<uint32_t>(offset), static_cast<uint32_t>(code_points),
                                              &rects, &count);
        if (status < 0) throw std::runtime_error("Selection rects failed");
        auto result = env->NewFloatArray(static_cast<jsize>(count));
        if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(count), rects);
        dc_selection_rects_destroy(rects);
        return result;
    } catch (const std::exception &e) { failure(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL Java_chimahon_renderer_DictionaryCore_sourceText(JNIEnv *env, jobject,
                                                                                       jlong handle, jint source) {
    try {
        auto &d = object<Drawing>(handle);
        if (!d.plan) throw std::invalid_argument("Missing drawing plan");
        uint16_t *text = nullptr;
        size_t length = 0;
        const int status = dc_source_text(d.plan.get(), static_cast<uint32_t>(source), &text, &length);
        if (status < 0) throw std::runtime_error("Source text failed");
        jstring result = text ? env->NewString(reinterpret_cast<const jchar *>(text),
                                               static_cast<jsize>(length)) : nullptr;
        dc_source_text_destroy(text);
        return result;
    } catch (const std::exception &e) { failure(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_chimahon_renderer_DictionaryCore_drawingTimings(JNIEnv *env, jobject,
                                                                                              jlong handle) {
    try {
        auto &d = object<Drawing>(handle);
        jfloat values[3] = {d.parse_ms, d.style_ms, d.begin_ms};
        auto result = env->NewFloatArray(3);
        if (result) env->SetFloatArrayRegion(result, 0, 3, values);
        return result;
    } catch (const std::exception &e) { failure(env, e.what()); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL Java_chimahon_renderer_DictionaryCore_profileBegin(JNIEnv *, jobject, jlong id) {
    // The host asks for native scopes whenever its own profiler runs, but the
    // native clock is far more expensive than the host's on some devices, so the
    // native side keeps its own switch: 1 = document phases, 2 = per element too.
    const int mode = profile_mode();
    if (mode == 2) dc_profile::begin(id, true);
    else if (mode == 1) dc_profile::begin(id, false);
}

extern "C" JNIEXPORT void JNICALL Java_chimahon_renderer_DictionaryCore_profileEnd(JNIEnv *, jobject) {
    dc_profile::end();
}
