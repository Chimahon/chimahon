#ifndef DC_DOCUMENT_LAYOUT_H
#define DC_DOCUMENT_LAYOUT_H
#include "styled_document.h"
#ifdef __cplusplus
extern "C" {
#endif
typedef struct dc_layout_document dc_layout_document;
typedef struct dc_text_measurement { uint64_t handle; float min_width, max_width; } dc_text_measurement;
typedef struct dc_text_geometry { float width, height; uint32_t lines; } dc_text_geometry;
/* One prepared handle per paragraph. Intrinsic widths and allocated wrapping use
 * the SAME handle; no JSON, selector matching, or semantic lowering in callbacks.
 * Callbacks must not throw. Context must outlive the returned layout document. */
typedef struct dc_text_engine {
    void* context;
    int (*prepare)(void*, const dc_prepared_view*, uint32_t paragraph, uint32_t needs_intrinsic, dc_text_measurement*, dc_error*);
    int (*wrap)(void*, uint64_t handle, float width, dc_text_geometry*, dc_error*);
    void (*release)(void*, uint64_t handle);
} dc_text_engine;
typedef struct dc_paragraph_placement {
    uint32_t paragraph, source;
    uint64_t handle;
    float x, y, width, height;
    uint32_t lines;
    float marker_x;
} dc_paragraph_placement;
typedef struct dc_cell_placement { uint32_t cell; float x,y,width,height; } dc_cell_placement;
typedef struct dc_source_geometry { float width,height; } dc_source_geometry;
typedef struct dc_block_placement { uint32_t style,source,cell; float x,y,width,height; } dc_block_placement;
typedef struct dc_layout_view {
    const dc_paragraph_placement* paragraphs; size_t paragraph_count;
    const dc_cell_placement* cells; size_t cell_count;
    const dc_source_geometry* sources; size_t source_count;
    uint64_t text_prepares, text_wraps, unresolved_lengths;
    const dc_block_placement* blocks; size_t block_count;
} dc_layout_view;
DC_API int dc_layout(const dc_prepared_document*, const dc_text_engine*,
                     const float* source_widths, size_t source_count,
                     dc_layout_document**, dc_error*);
DC_API dc_layout_view dc_layout_document_view(const dc_layout_document*);
DC_API void dc_layout_document_destroy(dc_layout_document*);
#ifdef __cplusplus
}
#endif
#endif
