#ifndef DC_STYLED_DOCUMENT_H
#define DC_STYLED_DOCUMENT_H
#include "dictionary_core.h"
#ifdef __cplusplus
extern "C" {
#endif
typedef struct dc_stylesheets dc_stylesheets;
typedef struct dc_prepared_document dc_prepared_document;
typedef struct dc_css_source { const char* utf8; size_t bytes; } dc_css_source;
typedef struct dc_compile_options {
    float font_size_px;
    int dark_mode, expand_details;
    float minimum_font_size_px; /* CSS pixels; zero disables the host minimum. */
} dc_compile_options;
typedef struct dc_text_run {
    uint32_t text_offset, text_bytes, style, role, ruby, paragraph;
} dc_text_run;
typedef struct dc_paragraph_input {
    uint32_t first_run, run_count, source, cell;
    uint32_t style;
    uint32_t marker_offset, marker_bytes, marker_style;
} dc_paragraph_input;
typedef struct dc_ruby_input {
    uint32_t first_base_run, base_run_count, first_reading_run, reading_run_count;
} dc_ruby_input;
typedef struct dc_table_input { uint32_t first_row, row_count, source, cell, style; } dc_table_input;
typedef struct dc_row_input { uint32_t first_cell, cell_count, table; } dc_row_input;
typedef struct dc_cell_input {
    uint32_t first_paragraph, paragraph_count, row, column_span, row_span, style;
} dc_cell_input;
typedef struct dc_style_property {
    uint32_t name_offset, name_bytes, value_offset, value_bytes;
} dc_style_property;
typedef struct dc_style_input { uint32_t first_property, property_count; float font_size_px; } dc_style_input;

/* Ordered flow events; cell=UINT32_MAX means the source's outer flow.
 * kind: 0 paragraph(index), 1 table(index), 2 block-start(style), 3 block-end(style).
 * Nested cells/tables retain explicit ownership instead of relying on contiguous ranges. */
typedef struct dc_flow_input { uint32_t kind, index, source, cell; } dc_flow_input;
typedef struct dc_image_input { uint32_t run, first_property, property_count; } dc_image_input;
/* Inline ancestor boundaries survive lowering; descendants do not inherit box decoration. */
typedef struct dc_inline_box { uint32_t first_run, run_count, style; } dc_inline_box;
typedef struct dc_prepared_view {
    const char* text; size_t text_bytes;
    const dc_text_run* runs; size_t run_count;
    const dc_paragraph_input* paragraphs; size_t paragraph_count;
    const dc_ruby_input* rubies; size_t ruby_count;
    const dc_table_input* tables; size_t table_count;
    const dc_row_input* rows; size_t row_count;
    const dc_cell_input* cells; size_t cell_count;
    const dc_style_input* styles; size_t style_count;
    const dc_style_property* properties; size_t property_count;
    const dc_flow_input* flow; size_t flow_count;
    const dc_image_input* images; size_t image_count;
    size_t source_count;
    uint64_t styled_elements, unresolved_values, unsupported_nodes;
    float base_font_size_px;
    const dc_inline_box* inline_boxes; size_t inline_box_count;
} dc_prepared_view;

/* Sheet zero is the base-only sheet; each remaining source is a COMPLETE
 * base+theme+dictionary sheet. Context is single-worker owned, not thread-safe.
 * Compiled input and output documents have independent lifetimes. */
DC_API int dc_stylesheets_create(const dc_css_source* sources, size_t count,
                                dc_stylesheets** output, dc_error* error);
DC_API void dc_stylesheets_destroy(dc_stylesheets* sheets);
/* Diagnostic only: verify rejected selector candidates against the full matcher.
 * Enable outside timing; disabled by default. Does not alter output semantics. */
DC_API void dc_stylesheets_verify_candidates(dc_stylesheets*, int enabled);
DC_API int dc_compile_styled(const dc_document* input, dc_stylesheets* sheets,
                            const uint32_t* sheet_for_source, size_t source_count,
                            dc_prepared_document** output, dc_error* error);
DC_API int dc_compile_styled_configured(const dc_document*, dc_stylesheets*, const uint32_t*, size_t,
                                       const dc_compile_options*, dc_prepared_document**, dc_error*);
/* All returned pointers remain valid until the prepared document is destroyed.
 * role: 0=text, 1=ruby base, 2=reading, 3=image placeholder. UINT32_MAX=no owner.
 * This is preparation input, not shaped/final geometry or a paint list. */
DC_API dc_prepared_view dc_prepared_document_view(const dc_prepared_document* document);
DC_API uint64_t dc_prepared_document_digest(const dc_prepared_document* document);
DC_API void dc_prepared_document_destroy(dc_prepared_document* document);
#ifdef __cplusplus
}
#endif
#endif
