#pragma once
#include "portable_renderer.h"
#include <vector>

// Immutable process-wide file catalog. FreeType opens only faces actually used.
const std::vector<dc_font_file> &dictionary_system_fonts();
