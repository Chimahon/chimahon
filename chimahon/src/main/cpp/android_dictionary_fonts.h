#pragma once
#include "portable_renderer.h"
#include <vector>

// Immutable process-wide file catalog, ranked for one language and generic family. FreeType opens only faces actually used.
const std::vector<dc_font_file> &dictionary_system_fonts(const char *language = nullptr,
                                                         const char *generic_family = nullptr);
