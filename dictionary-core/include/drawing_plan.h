#ifndef DC_DRAWING_PLAN_H
#define DC_DRAWING_PLAN_H
#include "document_layout.h"
#ifdef __cplusplus
extern "C" {
#endif
typedef struct dc_drawing_plan dc_drawing_plan;
/* CSS-pixel specifications. flags: italic=1, underline=2, strike=4, rtl=8,
 * nowrap=16, center=32, end=64, justify=128, preserve-spaces=256. */
typedef struct dc_drawing_font {
    float size, line_height, letter_spacing, baseline_shift;
    uint32_t weight, flags, color, background, border;
    float stroke, radius, padding_left, padding_right, padding_top, padding_bottom;
    uint32_t family_offset, family_bytes;
} dc_drawing_font;
typedef struct dc_measure_request {
    uint32_t style, start, count, context_start, context_count;
} dc_measure_request;
typedef struct dc_measure_context {
    uint32_t source, start, count, annotation;
} dc_measure_context;
typedef struct dc_run_metrics {
    float ascent, descent, em_ascent, em_descent;
} dc_run_metrics;
/* kind: text=0, rounded rectangle=1, image=2, two-stop radial gradient=3.
 * Gradient color/border hold the stops, stroke/radius their positions; flag 1 clips a circle.
 * Rectangle flags 8..11 select a single rounded border edge (top/right/bottom/left).
 * Text offsets are into the shared
 * UTF-16 buffer. resource indexes the request, or the original image record.
 * Text flags: rtl=1, annotation=2, static=4, ruby-base=8, halt-punctuation=16. */
typedef struct dc_draw_command {
    uint32_t kind, source, resource, start, count, style;
    float x, y, width, height, baseline;
    uint32_t color, border;
    float stroke, radius;
    uint32_t flags;
} dc_draw_command;
typedef struct dc_drawing_hit {
    uint32_t source, request, offset;
    float x, y, width, height;
} dc_drawing_hit;
typedef struct dc_drawing_source {
    float width, height;
    uint32_t first_command, command_count, first_hit, hit_count;
} dc_drawing_source;
typedef struct dc_drawing_view {
    const dc_drawing_font *fonts;
    size_t font_count;
    const char *names;
    size_t names_bytes;
    const uint16_t *text;
    size_t text_count;
    const dc_measure_request *requests;
    size_t request_count;
    const dc_measure_context *contexts;
    size_t context_count;
    dc_run_metrics *metrics;
    float *advances;
    uint8_t *breaks; /* bit0: legal boundary; bits1..3: OTHER,OPEN,CLOSE,MIDDLE,OPEN_NARROW,CLOSE_NARROW font facts */
    uint8_t *levels; /* resolved Unicode bidi level per UTF-16 unit */
    const dc_draw_command *commands;
    size_t command_count;
    const dc_drawing_hit *hits;
    size_t hit_count;
    const dc_drawing_source *sources;
    size_t source_count;
    uint64_t lines, ruby_groups;
} dc_drawing_view;
/* Begin exports ALL requests. Fill metrics/advances/breaks/levels in one bulk
 * platform phase, then finish without ANY platform callbacks. Buffers are stable
 * until destruction. Input must outlive the plan; finish is single-use. */
DC_API int dc_drawing_begin(const dc_prepared_document *, const float *widths, size_t, uint32_t foreground,
                            uint32_t border, dc_drawing_plan **, dc_error *);
DC_API dc_drawing_view dc_drawing_plan_view(dc_drawing_plan *);
DC_API int dc_drawing_finish(dc_drawing_plan *, dc_error *);
DC_API void dc_drawing_plan_destroy(dc_drawing_plan *);
#ifdef __cplusplus
}
#endif
#endif
