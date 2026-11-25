#pragma once
#include "drawing_plan.h"
#include "styled_document.h"
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace dictionary_core {

struct LinkSpan {
    uint32_t first_run, run_count, href_offset, href_bytes;
};

struct PreparedLinks {
    std::string text;
    std::vector<LinkSpan> spans;
};

const PreparedLinks &prepared_links(const dc_prepared_document *document);
std::string_view lookup_link_at(const dc_drawing_plan *plan, uint32_t source, float x, float y);

} // namespace dictionary_core
