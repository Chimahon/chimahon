#include "dictionary_core.h"
#include "drawing_plan.h"
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

bool is_boundary(uint16_t c) {
    switch (c) {
    case 0x3002: case 0xFF01: case 0xFF1F: case '.':
    case '!': case '?': case '\n': case '\r':
        return true;
    default:
        return false;
    }
}

/* Kotlin Char.isWhitespace */
bool is_space(uint16_t c) {
    const unsigned v = c;
    return v == 0x20 || (v >= 0x09 && v <= 0x0D) || (v >= 0x1C && v <= 0x1F) ||
           v == 0x1680 || (v >= 0x2000 && v <= 0x2006) || (v >= 0x2008 && v <= 0x200A) ||
           v == 0x2028 || v == 0x2029 || v == 0x205F || v == 0x3000;
}

bool same_word(const uint16_t *text, size_t length, const uint16_t *word, size_t word_length) {
    if (word_length > length)
        return false;
    for (size_t i = 0; i < word_length; ++i)
        if (text[i] != word[i])
            return false;
    return true;
}

uint16_t *copy(const uint16_t *text, size_t length, size_t *out_length) {
    if (out_length)
        *out_length = length;
    if (!length)
        return nullptr;
    auto *storage = static_cast<uint16_t *>(std::malloc(length * sizeof(uint16_t)));
    if (storage)
        std::memcpy(storage, text, length * sizeof(uint16_t));
    return storage;
}

} // namespace

int dc_hit_lookup(dc_drawing_plan *plan, uint32_t source, float x, float y,
                  dc_hit_lookup_result *result, dc_error *error) {
    if (result)
        *result = {};
    if (result)
        result->character = -1, result->text_offset = -1;
    if (!plan || !result)
        return -1;
    try {
        const auto view = dc_drawing_plan_view(plan);
        if (source >= view.source_count)
            return 0;
        const auto &item = view.sources[source];
        int32_t offset = -1;
        for (uint32_t i = item.first_hit + item.hit_count; i-- > item.first_hit;) {
            const auto &hit = view.hits[i];
            if (x < hit.x || y < hit.y || x >= hit.x + hit.width || y >= hit.y + hit.height)
                continue;
            offset = static_cast<int32_t>(hit.offset);
            break;
        }
        if (offset < 0)
            return 0;
        result->offset = offset;
        if (view.text && static_cast<size_t>(offset) < view.text_count)
            result->character = view.text[offset];

        int32_t body_start = -1, body_count = 0;
        for (size_t i = 0; i < view.context_count; ++i) {
            const auto &context = view.contexts[i];
            if (context.source != source || (context.annotation & 1) != 0)
                continue;
            if (static_cast<uint32_t>(offset) >= context.start &&
                static_cast<uint32_t>(offset) < context.start + context.count) {
                body_start = static_cast<int32_t>(context.start);
                body_count = static_cast<int32_t>(context.count);
                break;
            }
        }
        if (body_start < 0)
            return 1;

        const auto *body = view.text + body_start;
        const int32_t local = offset - body_start;
        const uint16_t *word = nullptr;
        size_t word_length = 0;
        if (!dc_lookup_word_at(body, static_cast<size_t>(body_count), local, &word, &word_length))
            return 1;

        int32_t find = -1;
        const int32_t limit = std::min<int32_t>(local, body_count - static_cast<int32_t>(word_length));
        for (int32_t at = limit; at >= 0; --at) {
            if (same_word(body + at, body_count - at, word, word_length)) {
                find = at;
                break;
            }
        }
        const int32_t lookup_start = find >= 0 && local < find + static_cast<int32_t>(word_length)
                                         ? find
                                         : local;

        int32_t sentence_start = local;
        while (sentence_start > 0 && !is_boundary(body[sentence_start - 1]))
            --sentence_start;
        int32_t sentence_end = local;
        while (sentence_end < body_count && !is_boundary(body[sentence_end]))
            ++sentence_end;
        if (sentence_end < body_count)
            ++sentence_end;
        while (sentence_start < local && is_space(body[sentence_start]))
            ++sentence_start;
        while (sentence_end > sentence_start && is_space(body[sentence_end - 1]))
            --sentence_end;

        result->text_offset = body_start + lookup_start;
        result->sentence_offset = local - sentence_start;
        result->word = copy(word, word_length, &result->word_length);
        result->sentence = copy(body + sentence_start,
                                static_cast<size_t>(sentence_end - sentence_start),
                                &result->sentence_length);
        return 1;
    } catch (const std::exception &failure) {
        if (error)
            std::snprintf(error->message, sizeof(error->message), "%s", failure.what());
        return -1;
    }
}

void dc_hit_lookup_result_destroy(dc_hit_lookup_result *result) {
    if (!result)
        return;
    std::free(result->word);
    std::free(result->sentence);
    *result = {};
}

int dc_selection_rects(dc_drawing_plan *plan, uint32_t source, uint32_t offset, uint32_t code_points,
                       float **rects, size_t *count) {
    if (rects)
        *rects = nullptr;
    if (count)
        *count = 0;
    if (!plan || !rects || !count)
        return 1;
    const auto view = dc_drawing_plan_view(plan);
    if (source >= view.source_count)
        return 0;
    int32_t body_start = -1, body_count = 0;
    for (size_t i = 0; i < view.context_count; ++i) {
        const auto &context = view.contexts[i];
        if (context.source != source || (context.annotation & 1) != 0)
            continue;
        if (offset >= context.start && offset < context.start + context.count) {
            body_start = static_cast<int32_t>(context.start);
            body_count = static_cast<int32_t>(context.count);
            break;
        }
    }
    if (body_start < 0)
        return 0;
    const int32_t limit = body_start + body_count;
    int32_t end = static_cast<int32_t>(offset);
    uint32_t counted = 0;
    while (end < limit && counted < code_points) {
        const uint32_t unit = view.text[end];
        end += (unit >= 0xD800 && unit <= 0xDBFF && end + 1 < limit &&
                view.text[end + 1] >= 0xDC00 && view.text[end + 1] <= 0xDFFF)
                   ? 2
                   : 1;
        ++counted;
    }
    const auto &item = view.sources[source];
    std::vector<float> out;
    for (uint32_t i = item.first_hit; i < item.first_hit + item.hit_count; ++i) {
        const auto &hit = view.hits[i];
        if (static_cast<int32_t>(hit.offset) < static_cast<int32_t>(offset) ||
            static_cast<int32_t>(hit.offset) >= end)
            continue;
        out.push_back(hit.x);
        out.push_back(hit.y);
        out.push_back(hit.width);
        out.push_back(hit.height);
    }
    if (out.empty())
        return 0;
    auto *storage = static_cast<float *>(std::malloc(out.size() * sizeof(float)));
    if (!storage)
        return 1;
    std::memcpy(storage, out.data(), out.size() * sizeof(float));
    *rects = storage;
    *count = out.size();
    return 1;
}

void dc_selection_rects_destroy(float *rects) { std::free(rects); }

int dc_source_text(dc_drawing_plan *plan, uint32_t source, uint16_t **text, size_t *length) {
    if (text)
        *text = nullptr;
    if (length)
        *length = 0;
    if (!plan || !text || !length)
        return 1;
    const auto view = dc_drawing_plan_view(plan);
    std::u16string out;
    for (size_t i = 0; i < view.context_count; ++i) {
        const auto &context = view.contexts[i];
        if (context.source != source || (context.annotation & 1) != 0)
            continue;
        if (!out.empty())
            out.push_back(u'\n');
        out.append(reinterpret_cast<const char16_t *>(view.text + context.start), context.count);
    }
    if (out.empty())
        return 0;
    *text = copy(reinterpret_cast<const uint16_t *>(out.data()), out.size(), length);
    return *text ? 1 : 2;
}

void dc_source_text_destroy(uint16_t *text) { std::free(text); }
