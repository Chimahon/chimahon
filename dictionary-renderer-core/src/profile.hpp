#pragma once
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <string>
#include <unordered_map>
#include <vector>
#if defined(__ANDROID__)
#include <android/log.h>
#endif

/* Request-scoped native timings. begin()/end() are driven by the host around
 * one render batch, so every phase below accumulates per worker.
 *
 * The clock is not free everywhere: steady_clock::now() costs ~35 ns on a
 * desktop but ~9.8 us on the Android emulator (no vDSO fast path), so every
 * entry point below must do nothing at all while the profiler is off. That is
 * why Span is explicit instead of an always-running RAII timer: the previous
 * unconditional now() in ~Scope() made the instrumented build ~50x slower than
 * the same code on the desktop and turned the cascade profile into a
 * measurement of the profiler.
 *
 * The state is function-local thread_local: MinGW emits inline thread_local
 * initializers in every translation unit, which breaks the shared-library link. */
namespace dc_profile {

inline int64_t now() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

struct Cost {
    int64_t calls = 0;
    int64_t wall = 0;
};

inline bool &active() {
    static thread_local bool value = false;
    return value;
}
/* Deep spans sit inside the per-element loop and cost two clock reads each.
 * On a device where the clock is a syscall that is the difference between a
 * usable profile and a measurement of the profiler, so they are opt-in. */
inline bool &deep_mode() {
    static thread_local bool value = false;
    return value;
}
inline int64_t &request() {
    static thread_local int64_t value = 0;
    return value;
}
inline std::unordered_map<std::string, Cost> &costs() {
    static thread_local std::unordered_map<std::string, Cost> value;
    return value;
}
inline std::vector<std::string> &order() {
    static thread_local std::vector<std::string> value;
    return value;
}

inline void add(const char *name, int64_t wall) {
    if (!active())
        return;
    auto &cost = costs()[name];
    if (!cost.calls)
        order().emplace_back(name);
    ++cost.calls;
    cost.wall += wall;
}

/* Counters share the report with timings: wall stays 0, calls is the count. */
inline void count(const char *name, int64_t value = 1) {
    if (!active())
        return;
    auto &cost = costs()[name];
    if (!cost.calls)
        order().emplace_back(name);
    cost.calls += value;
}

/* An explicit span: open()/close() are no-ops unless profiling is running. */
struct Span {
    int64_t start = 0;
    int64_t total = 0;
};

inline void open(Span &span) {
    if (active() && deep_mode())
        span.start = now();
}
inline void close(Span &span) {
    if (active() && deep_mode())
        span.total += now() - span.start;
}

struct Scope {
    const char *name;
    int64_t start = 0;
    bool on;
    explicit Scope(const char *value) : name(value), on(active()) {
        if (on)
            start = now();
    }
    ~Scope() {
        if (on)
            add(name, now() - start);
    }
};

inline void begin(int64_t id, bool deep = false) {
    active() = true;
    deep_mode() = deep;
    request() = id;
    costs().clear();
    order().clear();
}

inline void end() {
    if (!active())
        return;
    active() = false;
    for (const auto &name : order()) {
        const auto &cost = costs()[name];
        std::fprintf(stderr, "PROFILE id=%lld %-22s calls=%lld wall=%.3f ms\n", (long long)request(),
                     name.c_str(), (long long)cost.calls, cost.wall / 1e6);
#if defined(__ANDROID__)
        __android_log_print(ANDROID_LOG_DEBUG, "DictProfileNative",
                            "{\"id\":%lld,\"phase\":\"%s\",\"calls\":%lld,\"wall_ns\":%lld,\"cpu_ns\":0}",
                            (long long)request(), name.c_str(), (long long)cost.calls,
                            (long long)cost.wall);
#endif
    }
}

} // namespace dc_profile

#define DC_PROFILE_CAT2(a, b) a##b
#define DC_PROFILE_CAT(a, b) DC_PROFILE_CAT2(a, b)
#define DC_PROFILE(name) ::dc_profile::Scope DC_PROFILE_CAT(dc_scope_, __LINE__)(name)
