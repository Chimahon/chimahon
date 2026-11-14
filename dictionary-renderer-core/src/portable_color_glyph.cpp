#include "portable_color_glyph.hpp"
#include <plutovg.h>
#include <agg_pixfmt_rgba.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <stdexcept>

namespace {
struct Layer {
    std::unique_ptr<plutovg_surface_t, decltype(&plutovg_surface_destroy)> surface;
    std::unique_ptr<plutovg_canvas_t, decltype(&plutovg_canvas_destroy)> canvas;
    Layer(int w, int h) : surface(plutovg_surface_create(w,h), plutovg_surface_destroy),
        canvas(surface ? plutovg_canvas_create(surface.get()) : nullptr, plutovg_canvas_destroy) {
        if (!surface || !canvas) throw std::bad_alloc();
    }
};
struct Painter {
    int width, height;
    std::vector<std::unique_ptr<Layer>> layers;
    Painter(int w, int h) : width(w), height(h) { layers.push_back(std::make_unique<Layer>(w,h)); }
    plutovg_canvas_t *canvas() { return layers.back()->canvas.get(); }
};
Painter &painter(void *data) { return *static_cast<Painter *>(data); }
hb_draw_funcs_t *outlines() {
    static auto *funcs = [] {
        auto *f = hb_draw_funcs_create();
        hb_draw_funcs_set_move_to_func(f, [](hb_draw_funcs_t *, void *d, hb_draw_state_t *, float x,float y,void *) {
            plutovg_canvas_move_to(static_cast<plutovg_canvas_t *>(d),x,y); }, nullptr,nullptr);
        hb_draw_funcs_set_line_to_func(f, [](hb_draw_funcs_t *, void *d, hb_draw_state_t *, float x,float y,void *) {
            plutovg_canvas_line_to(static_cast<plutovg_canvas_t *>(d),x,y); }, nullptr,nullptr);
        hb_draw_funcs_set_quadratic_to_func(f, [](hb_draw_funcs_t *, void *d, hb_draw_state_t *, float a,float b,float x,float y,void *) {
            plutovg_canvas_quad_to(static_cast<plutovg_canvas_t *>(d),a,b,x,y); }, nullptr,nullptr);
        hb_draw_funcs_set_cubic_to_func(f, [](hb_draw_funcs_t *, void *d, hb_draw_state_t *, float a,float b,float c,float e,float x,float y,void *) {
            plutovg_canvas_cubic_to(static_cast<plutovg_canvas_t *>(d),a,b,c,e,x,y); }, nullptr,nullptr);
        hb_draw_funcs_set_close_path_func(f, [](hb_draw_funcs_t *,void *d,hb_draw_state_t *,void *) {
            plutovg_canvas_close_path(static_cast<plutovg_canvas_t *>(d)); },nullptr,nullptr);
        hb_draw_funcs_make_immutable(f); return f;
    }();
    return funcs;
}
struct Stops {
    std::vector<plutovg_gradient_stop_t> colors;
    float first = 0, last = 1;
    plutovg_spread_method_t spread;
    explicit Stops(hb_color_line_t *line) {
        unsigned count = 0;
        count = hb_color_line_get_color_stops(line,0,&count,nullptr);
        std::vector<hb_color_stop_t> stops(count);
        hb_color_line_get_color_stops(line,0,&count,stops.data());
        if (!count) throw std::runtime_error("Color font has an empty gradient");
        first = stops.front().offset; last = std::max(first + .00001f, stops.back().offset);
        for (const auto &s : stops) colors.push_back({(s.offset-first)/(last-first),
            {hb_color_get_red(s.color)/255.f,hb_color_get_green(s.color)/255.f,
             hb_color_get_blue(s.color)/255.f,hb_color_get_alpha(s.color)/255.f}});
        const auto extend = hb_color_line_get_extend(line);
        spread = extend == HB_PAINT_EXTEND_REPEAT ? PLUTOVG_SPREAD_METHOD_REPEAT :
            extend == HB_PAINT_EXTEND_REFLECT ? PLUTOVG_SPREAD_METHOD_REFLECT : PLUTOVG_SPREAD_METHOD_PAD;
    }
};
hb_paint_funcs_t *paints() {
    static auto *funcs = [] {
        auto *f = hb_paint_funcs_create();
        hb_paint_funcs_set_push_transform_func(f, [](hb_paint_funcs_t *,void *d,float xx,float yx,float xy,float yy,float dx,float dy,void *) {
            auto *c=painter(d).canvas(); plutovg_canvas_save(c);
            const plutovg_matrix_t matrix{xx,yx,xy,yy,dx,dy}; plutovg_canvas_transform(c,&matrix);
        },nullptr,nullptr);
        const auto restore = +[](hb_paint_funcs_t *,void *d,void *) { plutovg_canvas_restore(painter(d).canvas()); };
        hb_paint_funcs_set_pop_transform_func(f,restore,nullptr,nullptr);
        hb_paint_funcs_set_pop_clip_func(f,restore,nullptr,nullptr);
        hb_paint_funcs_set_push_clip_glyph_func(f, [](hb_paint_funcs_t *,void *d,hb_codepoint_t glyph,hb_font_t *font,void *) {
            auto *c=painter(d).canvas(); plutovg_canvas_save(c); plutovg_canvas_new_path(c);
            hb_font_draw_glyph(font,glyph,outlines(),c); plutovg_canvas_clip(c);
        },nullptr,nullptr);
        hb_paint_funcs_set_push_clip_rectangle_func(f, [](hb_paint_funcs_t *,void *d,float x0,float y0,float x1,float y1,void *) {
            auto *c=painter(d).canvas(); plutovg_canvas_save(c); plutovg_canvas_clip_rect(c,x0,y0,x1-x0,y1-y0);
        },nullptr,nullptr);
        hb_paint_funcs_set_color_func(f, [](hb_paint_funcs_t *,void *d,hb_bool_t,hb_color_t color,void *) {
            auto *c=painter(d).canvas();
            plutovg_canvas_set_rgba(c,hb_color_get_red(color)/255.f,hb_color_get_green(color)/255.f,
                hb_color_get_blue(color)/255.f,hb_color_get_alpha(color)/255.f); plutovg_canvas_paint(c);
        },nullptr,nullptr);
        hb_paint_funcs_set_linear_gradient_func(f, [](hb_paint_funcs_t *,void *d,hb_color_line_t *line,
            float x0,float y0,float x1,float y1,float x2,float y2,void *) {
            Stops s(line); auto *c=painter(d).canvas();
            float dx=x1-x0,dy=y1-y0; const float ux=x2-x0,uy=y2-y0,norm=ux*ux+uy*uy;
            if (norm > 0) { const auto dot=(dx*ux+dy*uy)/norm; dx-=dot*ux; dy-=dot*uy; }
            plutovg_canvas_set_linear_gradient(c,x0+dx*s.first,y0+dy*s.first,x0+dx*s.last,y0+dy*s.last,
                s.spread,s.colors.data(),static_cast<int>(s.colors.size()),nullptr); plutovg_canvas_paint(c);
        },nullptr,nullptr);
        hb_paint_funcs_set_radial_gradient_func(f, [](hb_paint_funcs_t *,void *d,hb_color_line_t *line,
            float x0,float y0,float r0,float x1,float y1,float r1,void *) {
            Stops s(line); auto *c=painter(d).canvas(); const float dx=x1-x0,dy=y1-y0,dr=r1-r0;
            plutovg_canvas_set_radial_gradient(c,x0+dx*s.last,y0+dy*s.last,r0+dr*s.last,
                x0+dx*s.first,y0+dy*s.first,r0+dr*s.first,s.spread,s.colors.data(),static_cast<int>(s.colors.size()),nullptr);
            plutovg_canvas_paint(c);
        },nullptr,nullptr);
        hb_paint_funcs_set_push_group_func(f, [](hb_paint_funcs_t *,void *d,void *) {
            auto &p=painter(d); plutovg_matrix_t matrix; plutovg_canvas_get_matrix(p.canvas(),&matrix);
            p.layers.push_back(std::make_unique<Layer>(p.width,p.height)); plutovg_canvas_set_matrix(p.canvas(),&matrix);
        },nullptr,nullptr);
        hb_paint_funcs_set_pop_group_func(f, [](hb_paint_funcs_t *,void *d,hb_paint_composite_mode_t mode,void *) {
            auto &p=painter(d); auto source=std::move(p.layers.back()); p.layers.pop_back(); auto *c=p.canvas();
            auto *surface=source->surface.get();
            if (mode > HB_PAINT_COMPOSITE_MODE_XOR) {
                static const unsigned modes[]{agg::comp_op_plus,agg::comp_op_screen,agg::comp_op_overlay,
                    agg::comp_op_darken,agg::comp_op_lighten,agg::comp_op_color_dodge,agg::comp_op_color_burn,
                    agg::comp_op_hard_light,agg::comp_op_soft_light,agg::comp_op_difference,agg::comp_op_exclusion,agg::comp_op_multiply};
                const auto index=static_cast<unsigned>(mode)-HB_PAINT_COMPOSITE_MODE_PLUS;
                if (index >= sizeof(modes)/sizeof(modes[0])) throw std::runtime_error("Unsupported color-font blend mode");
                auto *dst=plutovg_surface_get_data(surface); const auto *back=plutovg_surface_get_data(p.layers.back()->surface.get());
                for (int i=0;i<p.width*p.height;++i) {
                    const auto b=dst[i*4],g=dst[i*4+1],r=dst[i*4+2],a=dst[i*4+3];
                    std::memcpy(dst+i*4,back+i*4,4);
                    agg::comp_op_adaptor_rgba_pre<agg::rgba8,agg::order_bgra>::blend_pix(modes[index],dst+i*4,r,g,b,a,255);
                }
                mode=HB_PAINT_COMPOSITE_MODE_SRC;
            }
            plutovg_canvas_save(c); const plutovg_matrix_t identity{1,0,0,1,0,0}; plutovg_canvas_set_matrix(c,&identity);
            plutovg_canvas_set_operator(c,static_cast<plutovg_operator_t>(mode));
            plutovg_canvas_set_texture(c,surface,PLUTOVG_TEXTURE_TYPE_PLAIN,1,nullptr);
            plutovg_canvas_paint(c); plutovg_canvas_restore(c);
        },nullptr,nullptr);
        hb_paint_funcs_make_immutable(f); return f;
    }();
    return funcs;
}
}

PortableColorGlyph portable_color_glyph(hb_font_t *font, uint32_t glyph, float pixel_size, uint32_t foreground) {
    const auto upem=hb_face_get_upem(hb_font_get_face(font));
    int old_x,old_y; hb_font_get_scale(font,&old_x,&old_y);
    struct Scale { hb_font_t *font; int x,y; ~Scale(){hb_font_set_scale(font,x,y);} } restore{font,old_x,old_y};
    hb_font_set_scale(font,upem,upem);
    hb_glyph_extents_t bounds{};
    if (!hb_font_get_glyph_extents(font,glyph,&bounds)) bounds={0,static_cast<int>(upem),static_cast<int>(upem),-static_cast<int>(upem)};
    const float scale=pixel_size/upem;
    PortableColorGlyph result;
    result.left=static_cast<int>(std::floor(bounds.x_bearing*scale))-1;
    result.top=static_cast<int>(std::floor(-bounds.y_bearing*scale))-1;
    result.width=static_cast<int>(std::ceil((bounds.x_bearing+bounds.width)*scale))-result.left+1;
    result.height=static_cast<int>(std::ceil(-(bounds.y_bearing+bounds.height)*scale))-result.top+1;
    if(result.width<=0 || result.height<=0 || result.width>2048 || result.height>2048)
        throw std::runtime_error("Invalid color glyph bounds");
    Painter painter(result.width,result.height);
    const plutovg_matrix_t matrix{scale,0,0,-scale,static_cast<float>(-result.left),static_cast<float>(-result.top)};
    plutovg_canvas_set_matrix(painter.canvas(),&matrix);
    hb_font_paint_glyph(font,glyph,paints(),&painter,0,
        HB_COLOR(foreground&255,(foreground>>8)&255,(foreground>>16)&255,foreground>>24));
    const auto bytes=static_cast<size_t>(result.width)*result.height*4;
    const auto *pixels=plutovg_surface_get_data(painter.layers.front()->surface.get());
    result.bgra.assign(pixels,pixels+bytes);
    return result;
}
