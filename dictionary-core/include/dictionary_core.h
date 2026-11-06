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
typedef struct dc_source { const char* utf8; size_t bytes; } dc_source;
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
DC_API dc_counts dc_document_counts(const dc_document* document);
DC_API uint64_t dc_document_digest(const dc_document* document);
DC_API void dc_document_destroy(dc_document* document);

#ifdef __cplusplus
}
#endif
#endif
