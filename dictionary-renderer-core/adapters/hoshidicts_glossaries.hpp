#pragma once
#include "dictionary_core.h"
#include <hoshidicts/query.hpp>
#include <span>
#include <vector>

namespace dictionary_core {
// Call after Hoshidicts has materialized the selected entries, before JNI
// constructs Java GlossaryEntry objects. Selection/order remain the caller's.
// The ordinary JSON/JNI path for WebView is independent and unchanged.
inline int prepare_hoshi_glossaries(std::span<const GlossaryEntry> entries,
                                   dc_document** output, dc_error* error) {
    std::vector<dc_source> sources;
    sources.reserve(entries.size());
    for (const auto& entry : entries) sources.push_back({entry.glossary.data(), entry.glossary.size()});
    return dc_prepare_utf8(sources.data(), sources.size(), output, error);
}
}
