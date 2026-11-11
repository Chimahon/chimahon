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
 * one render batch, so every phase below accumulates per worker. The state is
 * function-local thread_local: MinGW emits inline thread_local initializers in
 * every translation unit, which breaks the shared-library link. */
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

struct Scope {
    const char *name;
    int64_t start;
    explicit Scope(const char *value) : name(value), start(active() ? now() : 0) {}
    ~Scope() { add(name, now() - start); }
};

inline void begin(int64_t id) {
    active() = true;
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
#if defined(__ANDROID__)
        __android_log_print(ANDROID_LOG_DEBUG, "DictProfileNative",
                            "{\"id\":%lld,\"phase\":\"%s\",\"calls\":%lld,\"wall_ns\":%lld,\"cpu_ns\":0}",
                            (long long)request(), name.c_str(), (long long)cost.calls,
                            (long long)cost.wall);
#else
        std::fprintf(stderr, "PROFILE id=%lld %-22s calls=%lld wall=%.3f ms\n", (long long)request(),
                     name.c_str(), (long long)cost.calls, cost.wall / 1e6);
#endif
    }
}

} // namespace dc_profile

#define DC_PROFILE_CAT2(a, b) a##b
#define DC_PROFILE_CAT(a, b) DC_PROFILE_CAT2(a, b)
#define DC_PROFILE(name) ::dc_profile::Scope DC_PROFILE_CAT(dc_scope_, __LINE__)(name)
