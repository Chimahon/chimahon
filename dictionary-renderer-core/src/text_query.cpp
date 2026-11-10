#include "dictionary_core.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct Run {
    size_t text, text_length, reading, reading_length;
};
using Runs = std::vector<Run>;

bool is_kana(uint16_t c) {
    const unsigned v = c;
    return (v >= 0x3040 && v <= 0x30FF) || (v >= 0x31F0 && v <= 0x31FF);
}

bool is_kanji(uint16_t c) {
    const unsigned v = c;
    return (v >= 0x4E00 && v <= 0x9FFF) || (v >= 0x3400 && v <= 0x4DBF) ||
           (v >= 0xF900 && v <= 0xFAFF);
}

/* Java Character.isWhitespace, which Kotlin isBlank() uses. */
bool is_space(uint16_t c) {
    const unsigned v = c;
    return v == 0x20 || (v >= 0x09 && v <= 0x0D) || (v >= 0x1C && v <= 0x1F) ||
           v == 0x1680 || (v >= 0x2000 && v <= 0x2006) || (v >= 0x2008 && v <= 0x200A) ||
           v == 0x2028 || v == 0x2029 || v == 0x205F || v == 0x3000;
}

bool is_blank(const uint16_t *text, size_t length) {
    for (size_t i = 0; i < length; ++i)
        if (!is_space(text[i]))
            return false;
    return true;
}

uint16_t to_hiragana(uint16_t c) {
    const unsigned v = c;
    return (v >= 0x30A1 && v <= 0x30F6) ? static_cast<uint16_t>(v - 0x60) : c;
}

std::u16string hiragana_copy(const uint16_t *text, size_t length) {
    std::u16string out;
    out.reserve(length);
    for (size_t i = 0; i < length; ++i)
        out.push_back(static_cast<char16_t>(to_hiragana(text[i])));
    return out;
}

bool all_kana(const uint16_t *text, size_t length) {
    for (size_t i = 0; i < length; ++i)
        if (!is_kana(text[i]))
            return false;
    return true;
}

bool starts_with(const std::u16string &haystack, size_t at, const char16_t *needle, size_t length) {
    if (at > haystack.size() || length > haystack.size() - at)
        return false;
    for (size_t i = 0; i < length; ++i)
        if (haystack[at + i] != needle[i])
            return false;
    return true;
}

bool same(const uint16_t *a, size_t an, const uint16_t *b, size_t bn) {
    if (an != bn)
        return false;
    for (size_t i = 0; i < an; ++i)
        if (a[i] != b[i])
            return false;
    return true;
}

/* Port of the Kotlin distributeFurigana: strict Yomitan-style pass, then an
 * edge-matching fallback. Runs are contiguous slices of expression/reading. */
Runs distribute_furigana(const uint16_t *expression, size_t expression_length, const uint16_t *reading,
                         size_t reading_length) {
    if (reading_length == 0 || is_blank(reading, reading_length) ||
        same(expression, expression_length, reading, reading_length))
        return {{0, expression_length, 0, 0}};

    const auto normalized = hiragana_copy(reading, reading_length);

    std::vector<size_t> group_start, group_length;
    for (size_t i = 0; i < expression_length;) {
        const bool kana = is_kana(expression[i]);
        size_t end = i + 1;
        while (end < expression_length && is_kana(expression[end]) == kana)
            ++end;
        group_start.push_back(i);
        group_length.push_back(end - i);
        i = end;
    }
    const size_t groups = group_start.size();

    if (groups <= 1)
        return {all_kana(expression, expression_length) ? Run{0, expression_length, 0, 0}
                                                        : Run{0, expression_length, 0, reading_length}};

    // A second solution at any kanji group makes the whole match ambiguous,
    // which the Kotlin reference treats as no match.
    std::function<std::optional<Runs>(size_t, size_t)> match = [&](size_t gi, size_t read_at)
        -> std::optional<Runs> {
        if (gi >= groups)
            return read_at >= reading_length ? std::optional<Runs>(Runs{}) : std::nullopt;
        const size_t start = group_start[gi], length = group_length[gi];
        if (all_kana(expression + start, length)) {
            const auto kana = hiragana_copy(expression + start, length);
            if (starts_with(normalized, read_at, kana.data(), length)) {
                auto rest = match(gi + 1, read_at + length);
                if (rest) {
                    Runs out{{start, length, 0, 0}};
                    out.insert(out.end(), rest->begin(), rest->end());
                    return out;
                }
            }
            return std::nullopt;
        }
        std::optional<Runs> result;
        size_t i = reading_length;
        while (i >= read_at + length) {
            auto rest = match(gi + 1, i);
            if (rest) {
                if (result)
                    return std::nullopt;
                Runs out{{start, length, read_at, i - read_at}};
                out.insert(out.end(), rest->begin(), rest->end());
                result = std::move(out);
            }
            if (i == 0)
                break;
            --i;
        }
        return result;
    };

    if (auto strict = match(0, 0))
        return *strict;

    // Fallback: match leading/trailing kana, distribute the middle reading.
    size_t read_pos = 0, front = 0;
    while (front < groups && all_kana(expression + group_start[front], group_length[front])) {
        const auto kana = hiragana_copy(expression + group_start[front], group_length[front]);
        if (!starts_with(normalized, read_pos, kana.data(), group_length[front]))
            break;
        read_pos += group_length[front];
        ++front;
    }

    int64_t back = static_cast<int64_t>(groups) - 1;
    size_t read_back = reading_length;
    while (back >= static_cast<int64_t>(front) &&
           all_kana(expression + group_start[back], group_length[back])) {
        const size_t length = group_length[back];
        if (read_back < length)
            break;
        const size_t end = read_back - length;
        const auto kana = hiragana_copy(expression + group_start[back], length);
        bool equal = end >= read_pos;
        for (size_t i = 0; equal && i < length; ++i)
            if (normalized[end + i] != kana[i])
                equal = false;
        if (!equal)
            break;
        read_back = end;
        --back;
    }

    Runs out;
    for (size_t i = 0; i < front; ++i)
        out.push_back({group_start[i], group_length[i], 0, 0});

    if (back >= static_cast<int64_t>(front)) {
        const size_t middle_reading = read_back >= read_pos ? read_back - read_pos : 0;
        size_t total_kanji = 0;
        for (int64_t i = static_cast<int64_t>(front); i <= back; ++i)
            if (!all_kana(expression + group_start[i], group_length[i]))
                total_kanji += group_length[i];
        if (total_kanji > 0) {
            size_t at = read_pos;
            for (int64_t i = static_cast<int64_t>(front); i <= back; ++i) {
                const size_t start = group_start[i], length = group_length[i];
                if (all_kana(expression + start, length)) {
                    out.push_back({start, length, 0, 0});
                } else {
                    const auto take = static_cast<size_t>(std::round(
                        (static_cast<float>(length) / static_cast<float>(total_kanji)) *
                        static_cast<float>(middle_reading)));
                    const size_t end = std::min(at + std::max(take, length), read_back);
                    out.push_back({start, length, at, end - at});
                    at = end;
                }
            }
        } else {
            for (int64_t i = static_cast<int64_t>(front); i <= back; ++i)
                out.push_back({group_start[i], group_length[i], 0, 0});
        }
    }

    for (int64_t i = back + 1; i < static_cast<int64_t>(groups); ++i)
        out.push_back({group_start[i], group_length[i], 0, 0});

    for (const auto &run : out)
        if (run.reading_length > 0)
            return out;
    return {{0, expression_length, 0, reading_length}};
}

/* Java regex \s is ASCII-only; U+3000 and the punctuation set are explicit. */
bool is_scan_boundary(uint16_t c) {
    switch (c) {
    case 0x20: case 0x09: case 0x0A: case 0x0B: case 0x0C: case 0x0D:
    case 0x3000: case 0x3002: case 0x3001: case 0xFF01: case 0xFF1F:
    case 0x2026: case 0x2025: case 0x300C: case 0x300D: case 0x300E:
    case 0x300F: case 0xFF08: case 0xFF09: case 0x0028: case 0x0029:
    case 0x3010: case 0x3011: case 0x3008: case 0x3009: case 0x300A:
    case 0x300B: case 0x3014: case 0x3015: case 0xFF5B: case 0xFF5D:
    case 0x007B: case 0x007D: case 0x005B: case 0x005D: case 0x30FB:
    case 0xFF1A: case 0xFF1B: case 0x003A: case 0x003B: case 0xFF0C:
    case 0x002C: case 0x002E: case 0x2500:
        return true;
    default:
        return false;
    }
}

bool is_lookup_cjk(uint16_t c) {
    const unsigned v = c;
    return (v >= 0x3040 && v <= 0x30FF) || (v >= 0x3000 && v <= 0x9FFF) ||
           (v >= 0xF900 && v <= 0xFAFF) || (v >= 0xFF00 && v <= 0xFFEF) ||
           (v >= 0x1100 && v <= 0x11FF) || (v >= 0x3130 && v <= 0x318F) ||
           (v >= 0x3400 && v <= 0x4DBF) || (v >= 0xAC00 && v <= 0xD7AF);
}

bool is_lookup_word(uint16_t c) {
    const unsigned v = c;
    if (is_lookup_cjk(c))
        return true;
    if ((v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9') || v == '_')
        return true;
    return (v >= 0x00C0 && v <= 0x024F) || (v >= 0x0600 && v <= 0x06FF);
}

} // namespace

int dc_lookup_word_at(const uint16_t *text, size_t length, int32_t offset, const uint16_t **word,
                      size_t *word_length) {
    if (word)
        *word = nullptr;
    if (word_length)
        *word_length = 0;
    if (!text || !length || !word || !word_length)
        return 0;
    size_t at = offset < 0 ? 0 : static_cast<size_t>(offset);
    if (at >= length)
        at = length - 1;
    const uint16_t start = text[at];
    if (is_scan_boundary(start) || !is_lookup_word(start))
        return 0;
    size_t begin = at, end = at;
    if (is_lookup_cjk(start)) {
        const size_t limit = std::min(length, at + 24);
        end = at;
        while (end < limit && !is_scan_boundary(text[end]))
            ++end;
    } else {
        begin = at;
        while (begin > 0 && !is_scan_boundary(text[begin - 1]) && is_lookup_word(text[begin - 1]))
            --begin;
        end = at + 1;
        while (end < length && !is_scan_boundary(text[end]) && is_lookup_word(text[end]))
            ++end;
    }
    if (end <= begin)
        return 0;
    *word = text + begin;
    *word_length = end - begin;
    return 1;
}

int dc_split_headword(const uint16_t *expression, size_t expression_length, const uint16_t *reading,
                      size_t reading_length, dc_headword_unit **units, size_t *count, dc_error *error) {
    if (units)
        *units = nullptr;
    if (count)
        *count = 0;
    if (!units || !count || (!expression && expression_length) || (!reading && reading_length))
        return 1;
    try {
        std::vector<dc_headword_unit> out;
        const auto runs = distribute_furigana(expression, expression_length, reading, reading_length);
        for (const auto &run : runs) {
            size_t kanji_count = 0;
            for (size_t i = 0; i < run.text_length; ++i)
                if (is_kanji(expression[run.text + i]))
                    ++kanji_count;
            if (kanji_count == 0) {
                const bool annotated = run.reading_length > 0 &&
                                       !is_blank(reading + run.reading, run.reading_length);
                out.push_back({annotated ? 2u : 0u, run.text, run.text_length,
                               annotated ? run.reading : 0, annotated ? run.reading_length : 0});
                continue;
            }
            size_t seen = 0;
            size_t buffer_start = 0;
            bool buffering = false;
            for (size_t i = 0; i < run.text_length; ++i) {
                const uint16_t c = expression[run.text + i];
                if (!is_kanji(c)) {
                    if (!buffering) {
                        buffer_start = run.text + i;
                        buffering = true;
                    }
                    continue;
                }
                if (buffering) {
                    out.push_back({0u, buffer_start, (run.text + i) - buffer_start, 0, 0});
                    buffering = false;
                }
                const size_t begin = (seen * run.reading_length) / kanji_count;
                const size_t end = ((seen + 1) * run.reading_length) / kanji_count;
                out.push_back({1u, run.text + i, 1, run.reading + begin, end - begin});
                ++seen;
            }
            if (buffering)
                out.push_back({0u, buffer_start, (run.text + run.text_length) - buffer_start, 0, 0});
        }
        if (out.empty())
            return 0;
        auto *storage = static_cast<dc_headword_unit *>(std::malloc(out.size() * sizeof(dc_headword_unit)));
        if (!storage)
            throw std::bad_alloc();
        std::memcpy(storage, out.data(), out.size() * sizeof(dc_headword_unit));
        *units = storage;
        *count = out.size();
        return 0;
    } catch (const std::exception &failure) {
        if (error)
            std::snprintf(error->message, sizeof(error->message), "%s", failure.what());
        return 1;
    }
}

void dc_headword_units_destroy(dc_headword_unit *units) { std::free(units); }

namespace {

constexpr uint16_t kSmallKana[] = {0x3041, 0x3043, 0x3045, 0x3047, 0x3049, 0x3083, 0x3085,
                                   0x3087, 0x308E, 0x30A1, 0x30A3, 0x30A5, 0x30A7, 0x30A9,
                                   0x30E3, 0x30E5, 0x30E7, 0x30EE};

bool small_kana(uint16_t c) {
    for (const auto kana : kSmallKana)
        if (kana == c)
            return true;
    return false;
}

} // namespace

int dc_split_morae(const uint16_t *text, size_t length, dc_text_slice **morae, size_t *count) {
    if (morae)
        *morae = nullptr;
    if (count)
        *count = 0;
    if (!morae || !count || (!text && length))
        return 1;
    std::vector<dc_text_slice> out;
    out.reserve(length);
    for (size_t i = 0; i < length; ++i) {
        if (small_kana(text[i]) && !out.empty())
            ++out.back().length;
        else
            out.push_back({static_cast<uint32_t>(i), 1});
    }
    if (out.empty())
        return 0;
    auto *storage = static_cast<dc_text_slice *>(std::malloc(out.size() * sizeof(dc_text_slice)));
    if (!storage)
        return 1;
    std::memcpy(storage, out.data(), out.size() * sizeof(dc_text_slice));
    *morae = storage;
    *count = out.size();
    return 0;
}

void dc_text_slices_destroy(dc_text_slice *slices) { std::free(slices); }

int dc_mora_high(size_t mora_index, int32_t accent) {
    if (accent == 0)
        return mora_index > 0;
    if (accent == 1)
        return mora_index < 1;
    return mora_index > 0 && mora_index < static_cast<size_t>(accent);
}

namespace {

void append_utf8(std::string &out, const uint16_t *text, size_t length) {
    for (size_t i = 0; i < length; ++i) {
        uint32_t c = text[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length && text[i + 1] >= 0xDC00 && text[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (text[i + 1] - 0xDC00);
            ++i;
        }
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (c >> 12)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (c >> 18)));
            out.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
}

struct TapeBuilder {
    std::vector<uint32_t> kinds, values;
    std::vector<std::string> strings;
    uint32_t string(const std::string &value) {
        strings.push_back(value);
        return static_cast<uint32_t>(strings.size() - 1);
    }
    void emit(uint32_t kind, uint32_t value) {
        kinds.push_back(kind);
        values.push_back(value);
    }
    void emit(uint32_t kind) { emit(kind, 0xFFFFFFFFu); }
};

int build_headword_tape(const uint16_t *expression, size_t expression_length, const uint16_t *reading,
                        size_t reading_length, const uint16_t *classes, size_t classes_length,
                        TapeBuilder &tape, dc_error *error) {
    dc_headword_unit *units = nullptr;
    size_t count = 0;
    if (dc_split_headword(expression, expression_length, reading, reading_length, &units, &count, error))
        return 1;
    std::string class_text;
    append_utf8(class_text, classes, classes_length);
    tape.emit(0, tape.string("div"));
    tape.emit(1, tape.string("class"));
    tape.emit(5, tape.string(class_text));
    for (size_t i = 0; i < count; ++i) {
        const auto &unit = units[i];
        std::string text;
        append_utf8(text, expression + unit.text_offset, unit.text_length);
        if (unit.kind == 0 || unit.reading_length == 0) {
            tape.emit(3, tape.string(text));
            continue;
        }
        std::string annotation;
        append_utf8(annotation, reading + unit.reading_offset, unit.reading_length);
        tape.emit(0, tape.string("ruby"));
        tape.emit(3, tape.string(text));
        tape.emit(0, tape.string("rt"));
        tape.emit(3, tape.string(annotation));
        tape.emit(4);
        tape.emit(4);
    }
    tape.emit(4);
    dc_headword_units_destroy(units);
    return 0;
}

size_t tape_bytes(const TapeBuilder &tape) {
    size_t strings = 0;
    for (const auto &value : tape.strings)
        strings += value.size();
    return 12 + tape.kinds.size() * 16 + strings;
}

} // namespace

int dc_headword_tape_size(const uint16_t *expression, size_t expression_length, const uint16_t *reading,
                          size_t reading_length, const uint16_t *classes, size_t classes_length,
                          size_t *bytes, dc_error *error) {
    if (bytes)
        *bytes = 0;
    if (!bytes)
        return 1;
    try {
        TapeBuilder tape;
        if (build_headword_tape(expression, expression_length, reading, reading_length, classes, classes_length,
                                tape, error))
            return 1;
        *bytes = tape_bytes(tape);
        return 0;
    } catch (const std::exception &failure) {
        if (error)
            std::snprintf(error->message, sizeof(error->message), "%s", failure.what());
        return 1;
    }
}

int dc_headword_tape_into(uint8_t *out, size_t capacity, const uint16_t *expression, size_t expression_length,
                          const uint16_t *reading, size_t reading_length, const uint16_t *classes,
                          size_t classes_length, size_t *written, dc_error *error) {
    if (written)
        *written = 0;
    if (!out || !written)
        return 1;
    try {
        TapeBuilder tape;
        if (build_headword_tape(expression, expression_length, reading, reading_length, classes, classes_length,
                                tape, error))
            return 1;
        const size_t total = tape_bytes(tape);
        if (total > capacity)
            throw std::length_error("Headword tape exceeds buffer");
        uint32_t header[3] = {0x43415244u, static_cast<uint32_t>(tape.kinds.size()), 0};
        for (const auto &value : tape.strings)
            header[2] += static_cast<uint32_t>(value.size());
        std::memcpy(out, header, sizeof(header));
        size_t at = sizeof(header);
        std::vector<uint32_t> offsets(tape.strings.size()), lengths(tape.strings.size());
        uint32_t start = 0;
        for (size_t i = 0; i < tape.strings.size(); ++i) {
            offsets[i] = start;
            lengths[i] = static_cast<uint32_t>(tape.strings[i].size());
            start += lengths[i];
        }
        for (size_t i = 0; i < tape.kinds.size(); ++i) {
            uint32_t record[4] = {tape.kinds[i], 0, 0, 0};
            if (tape.values[i] != 0xFFFFFFFFu) {
                record[2] = offsets[tape.values[i]];
                record[3] = lengths[tape.values[i]];
            }
            std::memcpy(out + at, record, sizeof(record));
            at += sizeof(record);
        }
        for (const auto &value : tape.strings) {
            std::memcpy(out + at, value.data(), value.size());
            at += value.size();
        }
        *written = at;
        return 0;
    } catch (const std::exception &failure) {
        if (error)
            std::snprintf(error->message, sizeof(error->message), "%s", failure.what());
        return 1;
    }
}
