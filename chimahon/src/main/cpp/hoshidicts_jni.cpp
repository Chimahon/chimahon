#include <jni.h>
#include <pthread.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "hoshidicts/include/hoshidicts.h"
#include "hoshidicts_profile.hpp"

namespace {
    struct Slot {
        uint64_t hash;
        uint64_t offset;
    };

    struct LookupObject {
        DictionaryQuery query;
        Deinflector deinflector;
        std::unique_ptr<Lookup> lookup;

        LookupObject() : lookup(std::make_unique<Lookup>(query, deinflector)) {}
    };

    LookupObject *as_object(jlong handle) { return reinterpret_cast<LookupObject *>(handle); }

    std::string jstring_to_std_string(JNIEnv *env, jstring input) {
        const char *chars = env->GetStringUTFChars(input, nullptr);
        std::string output(chars);
        env->ReleaseStringUTFChars(input, chars);
        return output;
    }

    template<typename Fn>
    void for_each_string(JNIEnv *env, jobjectArray arr, Fn fn) {
        const jsize count = env->GetArrayLength(arr);
        for (jsize i = 0; i < count; ++i) {
            auto element = reinterpret_cast<jstring>(env->GetObjectArrayElement(arr, i));
            fn(jstring_to_std_string(env, element));
            env->DeleteLocalRef(element);
        }
    }

    jstring new_string(JNIEnv *env, const std::string &value) {
        return env->NewStringUTF(value.c_str());
    }

    jobject new_import_result(JNIEnv *env, bool success, const std::string &title,
                              jlong term_count, jlong meta_count, jlong freq_count,
                              jlong pitch_count, jlong kanji_count, jlong media_count) {
        jclass cls = env->FindClass("chimahon/ImportResult");
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(ZLjava/lang/String;JJJJJJ)V");
        jstring jtitle = new_string(env, title);
        jobject out = env->NewObject(cls, ctor, static_cast<jboolean>(success), jtitle,
                                     term_count, meta_count, freq_count, pitch_count, kanji_count, media_count);
        env->DeleteLocalRef(jtitle);
        return out;
    }

    jobject new_transform_group(JNIEnv *env, const TransformGroup &tg) {
        jclass cls = env->FindClass("chimahon/TransformGroup");
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
        jstring name = new_string(env, tg.name);
        jstring desc = new_string(env, tg.description);
        jobject out = env->NewObject(cls, ctor, name, desc);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(desc);
        return out;
    }

    jobjectArray
    new_process_array(JNIEnv *env, const std::vector<TransformGroup> &trace) {
        jclass cls = env->FindClass("chimahon/TransformGroup");
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(trace.size()), cls, nullptr);
        for (size_t i = 0; i < trace.size(); ++i) {
            jobject item = new_transform_group(env, trace[i]);
            env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
            env->DeleteLocalRef(item);
        }
        return array;
    }

    jobject new_glossary_entry(JNIEnv *env, const GlossaryEntry &entry) {
        jclass cls = env->FindClass("chimahon/GlossaryEntry");
        jmethodID ctor =
                env->GetMethodID(cls, "<init>",
                                 "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        jstring dict_name = new_string(env, entry.dict_name);
        jstring glossary = new_string(env, entry.glossary);
        jstring definition_tags = new_string(env, entry.definition_tags);
        jstring term_tags = new_string(env, entry.term_tags);
        jobject out = env->NewObject(cls, ctor, dict_name, glossary, definition_tags, term_tags);
        env->DeleteLocalRef(dict_name);
        env->DeleteLocalRef(glossary);
        env->DeleteLocalRef(definition_tags);
        env->DeleteLocalRef(term_tags);
        return out;
    }

    jobjectArray new_glossary_entry_array(JNIEnv *env, const std::vector<GlossaryEntry> &entries) {
        jclass cls = env->FindClass("chimahon/GlossaryEntry");
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(entries.size()), cls, nullptr);
        for (size_t i = 0; i < entries.size(); ++i) {
            jobject item = new_glossary_entry(env, entries[i]);
            env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
            env->DeleteLocalRef(item);
        }
        return array;
    }

    jobject new_frequency(JNIEnv *env, const Frequency &frequency) {
        jclass cls = env->FindClass("chimahon/Frequency");
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
        jstring display_value = new_string(env, frequency.display_value);
        jobject out = env->NewObject(cls, ctor, static_cast<jint>(frequency.value), display_value);
        env->DeleteLocalRef(display_value);
        return out;
    }

    jobjectArray new_frequency_array(JNIEnv *env, const std::vector<Frequency> &frequencies) {
        jclass cls = env->FindClass("chimahon/Frequency");
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(frequencies.size()), cls,
                                                 nullptr);
        for (size_t i = 0; i < frequencies.size(); ++i) {
            jobject item = new_frequency(env, frequencies[i]);
            env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
            env->DeleteLocalRef(item);
        }
        return array;
    }

    jobject new_frequency_entry(JNIEnv *env, const FrequencyEntry &entry) {
        jclass cls = env->FindClass("chimahon/FrequencyEntry");
        jmethodID ctor = env->GetMethodID(cls, "<init>",
                                          "(Ljava/lang/String;[Lchimahon/Frequency;)V");
        jstring dict_name = new_string(env, entry.dict_name);
        jobjectArray frequencies = new_frequency_array(env, entry.frequencies);
        jobject out = env->NewObject(cls, ctor, dict_name, frequencies);
        env->DeleteLocalRef(dict_name);
        env->DeleteLocalRef(frequencies);
        return out;
    }

    jobjectArray
    new_frequency_entry_array(JNIEnv *env, const std::vector<FrequencyEntry> &entries) {
        jclass cls = env->FindClass("chimahon/FrequencyEntry");
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(entries.size()), cls, nullptr);
        for (size_t i = 0; i < entries.size(); ++i) {
            jobject item = new_frequency_entry(env, entries[i]);
            env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
            env->DeleteLocalRef(item);
        }
        return array;
    }

    jobject new_pitch_entry(JNIEnv *env, const PitchEntry &entry) {
        jclass cls = env->FindClass("chimahon/PitchEntry");
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;[I)V");
        jstring dict_name = new_string(env, entry.dict_name);
        jintArray positions = env->NewIntArray(static_cast<jsize>(entry.pitch_positions.size()));
        if (!entry.pitch_positions.empty()) {
            env->SetIntArrayRegion(positions, 0, static_cast<jsize>(entry.pitch_positions.size()),
                                   reinterpret_cast<const jint *>(entry.pitch_positions.data()));
        }
        jobject out = env->NewObject(cls, ctor, dict_name, positions);
        env->DeleteLocalRef(dict_name);
        env->DeleteLocalRef(positions);
        return out;
    }

    jobjectArray new_pitch_entry_array(JNIEnv *env, const std::vector<PitchEntry> &entries) {
        jclass cls = env->FindClass("chimahon/PitchEntry");
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(entries.size()), cls, nullptr);
        for (size_t i = 0; i < entries.size(); ++i) {
            jobject item = new_pitch_entry(env, entries[i]);
            env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
            env->DeleteLocalRef(item);
        }
        return array;
    }

    jobject new_term_result(JNIEnv *env, const TermResult &term) {
        jclass cls = env->FindClass("chimahon/TermResult");
        jmethodID ctor = env->GetMethodID(cls, "<init>",
                                          "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Lchimahon/GlossaryEntry;[Lchimahon/FrequencyEntry;[Lchimahon/PitchEntry;)V");
        jstring expression = new_string(env, term.expression);
        jstring reading = new_string(env, term.reading);
        jstring rules = new_string(env, term.rules);
        jobjectArray glossaries = new_glossary_entry_array(env, term.glossaries);
        jobjectArray frequencies = new_frequency_entry_array(env, term.frequencies);
        jobjectArray pitches = new_pitch_entry_array(env, term.pitches);
        jobject out = env->NewObject(cls, ctor, expression, reading, rules, glossaries, frequencies,
                                     pitches);
        env->DeleteLocalRef(expression);
        env->DeleteLocalRef(reading);
        env->DeleteLocalRef(rules);
        env->DeleteLocalRef(glossaries);
        env->DeleteLocalRef(frequencies);
        env->DeleteLocalRef(pitches);
        return out;
    }

    jobject new_lookup_result(JNIEnv *env, const LookupResult &result) {
        jclass cls = env->FindClass("chimahon/LookupResult");
        jmethodID ctor = env->GetMethodID(cls, "<init>",
                                          "(Ljava/lang/String;Ljava/lang/String;[Lchimahon/TransformGroup;Lchimahon/TermResult;I)V");
        jstring matched = new_string(env, result.matched);
        jstring deinflected = new_string(env, result.deinflected);
        jobjectArray process = new_process_array(env, result.trace);
        jobject term = new_term_result(env, result.term);
        jobject out = env->NewObject(cls, ctor, matched, deinflected, process, term,
                                     static_cast<jint>(result.preprocessor_steps));
        env->DeleteLocalRef(matched);
        env->DeleteLocalRef(deinflected);
        env->DeleteLocalRef(process);
        env->DeleteLocalRef(term);
        return out;
    }

    jobjectArray new_lookup_result_array(JNIEnv *env, const std::vector<LookupResult> &results) {
        jclass cls = env->FindClass("chimahon/LookupResult");
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(results.size()), cls, nullptr);
        for (size_t i = 0; i < results.size(); ++i) {
            jobject item = new_lookup_result(env, results[i]);
            env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
            env->DeleteLocalRef(item);
        }
        return array;
    }

    jobject new_dictionary_style(JNIEnv *env, const DictionaryStyle &style) {
        jclass cls = env->FindClass("chimahon/DictionaryStyle");
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");
        jstring dict_name = new_string(env, style.dict_name);
        jstring styles = new_string(env, style.styles);
        jobject out = env->NewObject(cls, ctor, dict_name, styles);
        env->DeleteLocalRef(dict_name);
        env->DeleteLocalRef(styles);
        return out;
    }

    jobjectArray
    new_dictionary_style_array(JNIEnv *env, const std::vector<DictionaryStyle> &styles) {
        jclass cls = env->FindClass("chimahon/DictionaryStyle");
        jobjectArray array = env->NewObjectArray(static_cast<jsize>(styles.size()), cls, nullptr);
        for (size_t i = 0; i < styles.size(); ++i) {
            jobject entry = new_dictionary_style(env, styles[i]);
            env->SetObjectArrayElement(array, static_cast<jsize>(i), entry);
            env->DeleteLocalRef(entry);
        }
        return array;
    }

    // Import thread arguments
    struct ImportArgs {
        std::string zip_path;
        std::string output_dir;
        ImportResult result;
    };

    // Thread function for import with large stack
    void* import_thread_func(void* arg) {
        ImportArgs* args = static_cast<ImportArgs*>(arg);
        args->result = dictionary_importer::import(args->zip_path, args->output_dir, true);
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_chimahon_HoshiDicts_createLookupObject(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new LookupObject());
}

extern "C" JNIEXPORT void JNICALL
Java_chimahon_HoshiDicts_destroyLookupObject(JNIEnv *, jobject, jlong session) {
    delete as_object(session);
}

extern "C" JNIEXPORT void JNICALL
Java_chimahon_HoshiDicts_rebuildQuery(JNIEnv *env, jobject, jlong session,
                                      jobjectArray term_paths, jobjectArray freq_paths,
                                      jobjectArray pitch_paths, jobjectArray kanji_paths) {
    LookupObject *obj = as_object(session);
    obj->query = DictionaryQuery{};
    for_each_string(env, term_paths,
                    [&](const std::string &path) { obj->query.add_term_dict(path); });
    for_each_string(env, freq_paths,
                    [&](const std::string &path) { obj->query.add_freq_dict(path); });
    for_each_string(env, pitch_paths,
                    [&](const std::string &path) { obj->query.add_pitch_dict(path); });
    for_each_string(env, kanji_paths,
                    [&](const std::string &path) { obj->query.add_kanji_dict(path); });
    obj->lookup = std::make_unique<Lookup>(obj->query, obj->deinflector);
}

extern "C" JNIEXPORT jobject JNICALL
Java_chimahon_HoshiDicts_queryKanji(JNIEnv *env, jobject, jlong session, jstring character) {
    LookupObject *obj = as_object(session);
    auto text_str = jstring_to_std_string(env, character);
    auto result = obj->query.query_kanji(text_str);

    jclass entryCls = env->FindClass("chimahon/KanjiEntry");
    jmethodID entryCtor = env->GetMethodID(entryCls, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/util/Map;)V");
    jclass mapCls = env->FindClass("java/util/HashMap");
    jmethodID mapCtor = env->GetMethodID(mapCls, "<init>", "()V");
    jmethodID mapPut = env->GetMethodID(mapCls, "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jclass strCls = env->FindClass("java/lang/String");

    jobjectArray entryArray = env->NewObjectArray(
        static_cast<jsize>(result.entries.size()), entryCls, nullptr);

    for (size_t i = 0; i < result.entries.size(); ++i) {
        const auto& e = result.entries[i];

        jobject dictName = new_string(env, e.dict_name);
        jobject onyomi = new_string(env, e.onyomi);
        jobject kunyomi = new_string(env, e.kunyomi);
        jobject tags = new_string(env, e.tags);

        jobjectArray defs = env->NewObjectArray(
            static_cast<jsize>(e.definitions.size()), strCls, nullptr);
        for (size_t j = 0; j < e.definitions.size(); ++j) {
            jobject def = new_string(env, e.definitions[j]);
            env->SetObjectArrayElement(defs, static_cast<jsize>(j), def);
            env->DeleteLocalRef(def);
        }

        jobject stats = env->NewObject(mapCls, mapCtor);
        for (const auto& [k, v] : e.stats) {
            jobject key = new_string(env, k);
            jobject val = new_string(env, v);
            env->CallObjectMethod(stats, mapPut, key, val);
            env->DeleteLocalRef(key);
            env->DeleteLocalRef(val);
        }

        jobject entry = env->NewObject(entryCls, entryCtor, dictName, onyomi, kunyomi, tags, defs, stats);
        env->SetObjectArrayElement(entryArray, static_cast<jsize>(i), entry);

        env->DeleteLocalRef(dictName);
        env->DeleteLocalRef(onyomi);
        env->DeleteLocalRef(kunyomi);
        env->DeleteLocalRef(tags);
        env->DeleteLocalRef(defs);
        env->DeleteLocalRef(stats);
        env->DeleteLocalRef(entry);
    }

    jclass resultCls = env->FindClass("chimahon/KanjiResult");
    jmethodID resultCtor = env->GetMethodID(resultCls, "<init>",
        "(Ljava/lang/String;[Lchimahon/KanjiEntry;)V");
    jobject character_jstr = new_string(env, result.character);
    jobject out = env->NewObject(resultCls, resultCtor, character_jstr, entryArray);
    env->DeleteLocalRef(character_jstr);
    env->DeleteLocalRef(entryArray);
    return out;
}

extern "C" JNIEXPORT jobject JNICALL
Java_chimahon_HoshiDicts_importDictionary(JNIEnv *env, jobject, jstring zip_path,
                                          jstring output_dir) {
    ImportArgs args;
    args.zip_path = jstring_to_std_string(env, zip_path);
    args.output_dir = jstring_to_std_string(env, output_dir);

    // Create thread with 16 MB stack to handle radix sort's large stack allocations
    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16 * 1024 * 1024);  // 16 MB stack

    int rc = pthread_create(&thread, &attr, import_thread_func, &args);
    pthread_attr_destroy(&attr);

    if (rc == 0) {
        pthread_join(thread, nullptr);
    } else {
        // Fallback: run on current thread (risky, but better than silent failure)
        args.result = dictionary_importer::import(args.zip_path, args.output_dir, true);
    }

    std::string title_or_error = args.result.title;
    if (!args.result.success && !args.result.errors.empty()) {
        title_or_error = args.result.errors[0];
    }
    return new_import_result(env, args.result.success, title_or_error,
                             static_cast<jlong>(args.result.term_count),
                             static_cast<jlong>(args.result.meta_count),
                             static_cast<jlong>(args.result.freq_count),
                             static_cast<jlong>(args.result.pitch_count),
                             static_cast<jlong>(args.result.kanji_count),
                             static_cast<jlong>(args.result.media_count));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_chimahon_HoshiDicts_lookup(JNIEnv *env, jobject, jlong session, jstring text,
                                jint max_results, jint scan_length) {
    LookupObject *obj = as_object(session);
    auto text_str = jstring_to_std_string(env, text);
    auto result = obj->lookup->lookup(text_str, static_cast<int>(max_results),
                                      static_cast<size_t>(scan_length));
    return new_lookup_result_array(env, result);
}

// DICT_PROFILE: separate entry point keeps the ordinary lookup path unchanged.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_chimahon_HoshiDicts_lookupProfiled(JNIEnv *env, jobject, jlong session, jstring text,
                                      jint max_results, jint scan_length, jlong request_id) {
    dict_profile::Context context(request_id);
    dict_profile::Scope total("native.total");
    LookupObject *obj = as_object(session);
    auto text_str = dict_profile::measure("jni.input", [&] { return jstring_to_std_string(env, text); });
    auto result = obj->lookup->lookup(text_str, static_cast<int>(max_results), static_cast<size_t>(scan_length));
    return dict_profile::measure("jni.results", [&] { return new_lookup_result_array(env, result); });
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_chimahon_HoshiDicts_query(JNIEnv *env, jobject, jlong session, jstring text) {
    LookupObject *obj = as_object(session);
    auto text_str = jstring_to_std_string(env, text);
    auto results = obj->query.query(text_str);
    jclass cls = env->FindClass("chimahon/TermResult");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(results.size()), cls, nullptr);
    for (size_t i = 0; i < results.size(); ++i) {
        jobject item = new_term_result(env, results[i]);
        env->SetObjectArrayElement(array, static_cast<jsize>(i), item);
        env->DeleteLocalRef(item);
    }
    return array;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_chimahon_HoshiDicts_getStyles(JNIEnv *env, jobject, jlong session) {
    LookupObject *obj = as_object(session);
    auto styles = obj->query.get_styles();
    return new_dictionary_style_array(env, styles);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_chimahon_HoshiDicts_getMediaFile(JNIEnv *env, jobject, jlong session,
                                      jstring dict_name, jstring media_path) {
    LookupObject *obj = as_object(session);
    auto dict_name_str = jstring_to_std_string(env, dict_name);
    auto media_path_str = jstring_to_std_string(env, media_path);
    auto data = obj->query.get_media_file(dict_name_str, media_path_str);
    if (data.empty()) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
                            reinterpret_cast<const jbyte *>(data.data()));
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_chimahon_HoshiDicts_probeEntryTypes(JNIEnv *env, jobject, jstring dict_path) {
    auto dir = jstring_to_std_string(env, dict_path);
    std::string hashPath = dir + "/hash.table";
    std::string blobsPath = dir + "/blobs.bin";

    auto make_zeros = [&]() {
        jlongArray result = env->NewLongArray(4);
        jlong zeros[4] = {0, 0, 0, 0};
        env->SetLongArrayRegion(result, 0, 4, zeros);
        return result;
    };

    FILE* hashFile = fopen(hashPath.c_str(), "rb");
    if (!hashFile) return make_zeros();

    uint32_t capacity;
    if (fread(&capacity, sizeof(capacity), 1, hashFile) != 1) {
        fclose(hashFile);
        return make_zeros();
    }

    std::vector<Slot> slots(capacity);
    if (fread(slots.data(), sizeof(Slot), capacity, hashFile) != capacity) {
        fclose(hashFile);
        return make_zeros();
    }
    fclose(hashFile);

    FILE* blobsFile = fopen(blobsPath.c_str(), "rb");
    if (!blobsFile) return make_zeros();

    fseek(blobsFile, 0, SEEK_END);
    size_t blobsSize = static_cast<size_t>(ftell(blobsFile));
    fseek(blobsFile, 0, SEEK_SET);
    std::vector<uint8_t> blobs(blobsSize);
    if (fread(blobs.data(), 1, blobsSize, blobsFile) != blobsSize) {
        fclose(blobsFile);
        return make_zeros();
    }
    fclose(blobsFile);

    uint64_t termCount = 0, freqCount = 0, pitchCount = 0, kanjiCount = 0;

    for (uint32_t i = 0; i < capacity; i++) {
        if (slots[i].hash == 0) continue;

        uint64_t blobOffset = slots[i].offset;
        if (blobOffset + sizeof(uint32_t) > blobsSize) continue;

        uint32_t entryCount;
        std::memcpy(&entryCount, blobs.data() + blobOffset, sizeof(entryCount));
        size_t idx = blobOffset + sizeof(entryCount);

        for (uint32_t j = 0; j < entryCount; j++) {
            if (idx + sizeof(uint64_t) > blobsSize) break;
            uint64_t entryOffset;
            std::memcpy(&entryOffset, blobs.data() + idx, sizeof(entryOffset));
            idx += sizeof(entryOffset);

            if (entryOffset + 1 > blobsSize) continue;
            uint8_t type = blobs[entryOffset];

            if (type == 0) {
                termCount++;
            } else if (type == 1) {
                size_t pos = entryOffset + 1;
                if (pos + 2 > blobsSize) continue;
                uint16_t exprLen;
                std::memcpy(&exprLen, blobs.data() + pos, sizeof(exprLen));
                pos += 2 + exprLen;
                if (pos + 1 > blobsSize) continue;
                uint8_t modeLen = blobs[pos];
                pos += 1;
                if (pos + static_cast<size_t>(modeLen) > blobsSize) continue;
                std::string mode(reinterpret_cast<const char*>(blobs.data() + pos), modeLen);

                if (mode == "freq") {
                    freqCount++;
                } else if (mode == "pitch") {
                    pitchCount++;
                }
            } else if (type == 2) {
                kanjiCount++;
            }
        }
    }

    jlongArray result = env->NewLongArray(4);
    jlong counts[4] = {
        static_cast<jlong>(termCount),
        static_cast<jlong>(freqCount),
        static_cast<jlong>(pitchCount),
        static_cast<jlong>(kanjiCount),
    };
    env->SetLongArrayRegion(result, 0, 4, counts);
    return result;
}
