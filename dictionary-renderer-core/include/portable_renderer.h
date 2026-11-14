#ifndef DC_PORTABLE_RENDERER_H
#define DC_PORTABLE_RENDERER_H
#include "drawing_plan.h"
#ifdef __cplusplus
extern "C" {
#endif

typedef struct dc_portable_document dc_portable_document;
/* Files are supplied by the host; font parsing, shaping and rasterization are portable.
 * First file is the requested family, remaining files are fallback faces.
 * The borrowed drawing plan must outlive this document. Access is single-threaded. */
typedef struct dc_font_file { const char *path; uint32_t collection_index; } dc_font_file;
DC_API int dc_portable_create(dc_drawing_plan *, const dc_font_file *, size_t,
                             dc_portable_document **, dc_error *);
/* Fills all existing bulk measurements and retains the glyph runs used for painting. */
DC_API int dc_portable_measure(dc_portable_document *, float pixel_scale, dc_error *);
/* Premultiplied RGBA pixels. The host supplies only a viewport, never a full long-page bitmap.
 * Image commands remain explicit host resources; all text/box/gradient drawing is native. */
DC_API int dc_portable_raster(dc_portable_document *, uint32_t source, float scale,
                            float scroll_x, float scroll_y, uint32_t width, uint32_t height,
                            uint8_t *pixels, size_t stride, dc_error *);
DC_API int dc_portable_raster_tinted(dc_portable_document *, uint32_t source, float scale,
                            float scroll_x, float scroll_y, uint32_t width, uint32_t height,
                            uint8_t *pixels, size_t stride, uint32_t text_color, int override_color, dc_error *);
DC_API void dc_portable_destroy(dc_portable_document *);
#ifdef __cplusplus
}
#endif
#endif
