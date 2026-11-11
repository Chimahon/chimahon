#ifndef DICTIONARY_CORE_H
#define DICTIONARY_CORE_H
#include <stddef.h>
#include <stdint.h>
#if defined(_WIN32)
#if defined(DC_BUILD)
#define DC_API __declspec(dllexport)
#else
#define DC_API __declspec(dllimport)
#endif
#else
#define DC_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif

typedef struct dc_document dc_document;
typedef struct dc_drawing_plan dc_drawing_plan;
typedef struct dc_source { const char* utf8; size_t bytes; } dc_source;
/* Host chrome: uint32 magic(0x43415244)/command_count/string_bytes, followed
 * by command records (kind/reserved/string_offset/string_length) and UTF-8.
 * Kinds: open=0,attribute=1,property=2,text=3,close=4,value=5.
 * Attribute/property commands are followed by a value, before child content. */
typedef struct dc_typed_source { const void *data; size_t bytes; } dc_typed_source;
typedef struct dc_error { uint32_t source; size_t offset; char message[192]; } dc_error;
typedef struct dc_counts {
    uint64_t sources, json_tokens, text_bytes, operations;
    uint64_t elements, ruby_groups, ruby_readings, tables, rows, cells, lists;
    uint64_t plain_sources, html_sources;
} dc_counts;

/* Sources are copied/decoded into owned storage. No input pointer is retained.
 * Phase one only: preserves source JSON/attributes and emits structural ops.
 * Does not resolve CSS, shape fonts, lay out paragraphs or paint. */
DC_API int dc_prepare_utf8(const dc_source* sources, size_t count,
                          dc_document** output, dc_error* error);
DC_API int dc_prepare_mixed(const dc_source*, const dc_typed_source*, size_t,
                           dc_document**, dc_error*);
DC_API dc_counts dc_document_counts(const dc_document* document);
DC_API uint64_t dc_document_digest(const dc_document* document);
DC_API void dc_document_destroy(dc_document* document);

/* Debug only: request-scoped native profiler. Every native phase between
 * begin() and end() on the calling thread is reported when end() runs. */
DC_API void dc_profile_begin(int64_t id);
DC_API void dc_profile_begin_deep(int64_t id);
DC_API void dc_profile_end(void);

/* Host text helpers. All offsets are UTF-16 code units, matching Java/Kotlin
 * strings and the drawing plan's text buffer. Natives own the algorithms so the
 * host keeps no word-breaking, furigana or character-class logic. */
typedef struct dc_headword_unit {
    uint32_t kind; /* 0 = text, 1 = kanji, 2 = ruby */
    size_t text_offset, text_length;
    size_t reading_offset, reading_length;
} dc_headword_unit;
/* 1 when the offset lands on a lookup word; the word is a slice of text. */
DC_API int dc_lookup_word_at(const uint16_t *text, size_t length, int32_t offset,
                             const uint16_t **word, size_t *word_length);
/* Splits a term into per-kanji/plain/ruby units. Slices point into the inputs. */
DC_API int dc_split_headword(const uint16_t *expression, size_t expression_length,
                             const uint16_t *reading, size_t reading_length,
                             dc_headword_unit **units, size_t *count, dc_error *error);
DC_API void dc_headword_units_destroy(dc_headword_unit *units);
/* Headword chrome as the host card tape (CARDR), so it skips the HTML parser. */
DC_API int dc_headword_tape_size(const uint16_t *expression, size_t expression_length,
                                 const uint16_t *reading, size_t reading_length,
                                 const uint16_t *classes, size_t classes_length,
                                 size_t *bytes, dc_error *error);
DC_API int dc_headword_tape_into(uint8_t *out, size_t capacity,
                                 const uint16_t *expression, size_t expression_length,
                                 const uint16_t *reading, size_t reading_length,
                                 const uint16_t *classes, size_t classes_length,
                                 size_t *written, dc_error *error);
/* Native hit testing, selection and copy text. Offsets are UTF-16 units and
 * rectangle coordinates are CSS pixels local to the source. */
typedef struct dc_hit_lookup_result {
    int32_t offset;         /* text offset under the point, -1 when none */
    int32_t character;      /* UTF-16 code unit at offset, -1 when unknown */
    int32_t text_offset;    /* lookup word start, -1 when none */
    int32_t sentence_offset;
    uint16_t *word;
    size_t word_length;
    uint16_t *sentence;
    size_t sentence_length;
} dc_hit_lookup_result;
DC_API int dc_hit_lookup(dc_drawing_plan *plan, uint32_t source, float x, float y,
                         dc_hit_lookup_result *result, dc_error *error);
DC_API void dc_hit_lookup_result_destroy(dc_hit_lookup_result *result);
DC_API int dc_selection_rects(dc_drawing_plan *plan, uint32_t source, uint32_t offset,
                              uint32_t code_points, float **rects, size_t *count);
DC_API void dc_selection_rects_destroy(float *rects);
DC_API int dc_source_text(dc_drawing_plan *plan, uint32_t source, uint16_t **text, size_t *length);
DC_API void dc_source_text_destroy(uint16_t *text);
/* Japanese moras; a small kana extends the previous mora. */
typedef struct dc_text_slice { uint32_t offset, length; } dc_text_slice;
DC_API int dc_split_morae(const uint16_t *text, size_t length, dc_text_slice **morae, size_t *count);
DC_API void dc_text_slices_destroy(dc_text_slice *slices);
/* Pitch-accent rule: 0 = heiban, 1 = atamadaka, otherwise downstep position. */
DC_API int dc_mora_high(size_t mora_index, int32_t accent);

#ifdef __cplusplus
}
#endif
#endif
