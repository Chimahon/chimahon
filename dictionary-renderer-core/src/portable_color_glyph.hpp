#pragma once
#include <hb.h>
#include <cstdint>
#include <vector>

struct PortableColorGlyph {
    int left = 0, top = 0, width = 0, height = 0;
    std::vector<uint8_t> bgra;
};
PortableColorGlyph portable_color_glyph(hb_font_t *, uint32_t glyph, float pixel_size, uint32_t foreground);
