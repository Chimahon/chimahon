#include "dictionary_core.h"
#include "document_layout.h"
#include "drawing_plan.h"
#include "styled_document.h"
#include <climits>
#include <cstdio>
#include <jni.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
template <class T, auto Destroy> using Owned = std::unique_ptr<T, decltype(Destroy)>;
struct Context {
    Owned<dc_stylesheets, dc_stylesheets_destroy> sheets{nullptr, dc_stylesheets_destroy};
    dc_compile_options options;
};
struct Drawing {
    Owned<dc_prepared_document, dc_prepared_document_destroy> prepared{nullptr, dc_prepared_document_destroy};
    Owned<dc_drawing_plan, dc_drawing_plan_destroy> plan{nullptr, dc_drawing_plan_destroy};
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
                                                                                   jboolean dark, jboolean expanded) {
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
extern "C" JNIEXPORT jlong JNICALL Java_chimahon_renderer_DictionaryCore_prepareDrawing(
    JNIEnv *env, jobject, jlong context, jobjectArray input, jintArray indices, jfloatArray widths, jint foreground,
    jint border) {
    try {
        if (!input || !indices || !widths)
            throw std::invalid_argument("Null drawing batch");
        auto &c = object<Context>(context);
        const auto count = env->GetArrayLength(input);
        if (env->GetArrayLength(indices) != count || env->GetArrayLength(widths) != count)
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
        std::vector<uint32_t> mapping(count);
        std::vector<float> sizes(count);
        env->GetIntArrayRegion(indices, 0, count, reinterpret_cast<jint *>(mapping.data()));
        env->GetFloatArrayRegion(widths, 0, count, sizes.data());
        if (env->ExceptionCheck())
            return 0;
        dc_document *parsed = nullptr;
        dc_error error{};
        if (dc_prepare_utf8(sources.data(), sources.size(), &parsed, &error))
            throw std::runtime_error(error.message);
        Owned<dc_document, dc_document_destroy> raw(parsed, dc_document_destroy);
        auto result = std::make_unique<Drawing>();
        dc_prepared_document *prepared = nullptr;
        if (dc_compile_styled_configured(parsed, c.sheets.get(), mapping.data(), mapping.size(), &c.options, &prepared,
                                         &error))
            throw std::runtime_error(error.message);
        result->prepared.reset(prepared);
        dc_drawing_plan *plan = nullptr;
        if (dc_drawing_begin(prepared, sizes.data(), sizes.size(), foreground, border, &plan, &error))
            throw std::runtime_error(error.message);
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
        const size_t sizes[]{
            v.font_count,    v.names_bytes,     v.text_count,   v.request_count, v.context_count,
            v.command_count, v.hit_count,       v.source_count, p.image_count,   p.property_count,
            p.text_bytes,    p.paragraph_count, p.ruby_count,   p.table_count,   static_cast<size_t>(v.lines)};
        jint values[15];
        for (size_t i = 0; i < 15; ++i) {
            if (sizes[i] > INT_MAX)
                throw std::length_error("Drawing packet limit");
            values[i] = static_cast<jint>(sizes[i]);
        }
        auto result = env->NewIntArray(15);
        if (result)
            env->SetIntArrayRegion(result, 0, 15, values);
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
        if (dc_drawing_finish(d.plan.get(), &error))
            throw std::runtime_error(error.message);
    } catch (const std::exception &e) {
        failure(env, e.what());
    } catch (...) {
        failure(env, "Drawing layout failed");
    }
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
