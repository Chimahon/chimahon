#pragma once

// DICT_PROFILE: request-local diagnostics, absent from the ordinary JNI lookup path.
#include <cstdint>
#include <cstdio>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#if defined(__ANDROID__)
#include <android/log.h>
#include <android/trace.h>
#endif
#if !defined(_WIN32)
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>
#else
#include <chrono>
#endif

namespace dict_profile {
inline int64_t wall_ns() {
#if !defined(_WIN32)
  timespec value{};
#if defined(__ANDROID__)
  clock_gettime(CLOCK_BOOTTIME, &value);
#else
  clock_gettime(CLOCK_MONOTONIC, &value);
#endif
  return int64_t(value.tv_sec) * 1000000000 + value.tv_nsec;
#else
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
      std::chrono::steady_clock::now().time_since_epoch()).count();
#endif
}
struct Sample {
  int64_t wall = wall_ns(), cpu = 0, minor = 0, major = 0;
  Sample() {
#if !defined(_WIN32)
    timespec value{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &value);
    cpu = int64_t(value.tv_sec) * 1000000000 + value.tv_nsec;
    rusage usage{};
    getrusage(RUSAGE_THREAD, &usage);
    minor = usage.ru_minflt;
    major = usage.ru_majflt;
#endif
  }
};
struct Cost {
  int64_t calls = 0, wall = 0, cpu = 0, minor = 0, major = 0;
};
struct Context;
inline thread_local Context* active = nullptr;
inline std::string quote(std::string_view value) {
  std::string out = "\"";
  for (unsigned char c : value) {
    if (c == '\\' || c == '"') { out += '\\'; out += c; }
    else if (c < 32) { char escaped[7]; snprintf(escaped, sizeof(escaped), "\\u%04x", c); out += escaped; }
    else out += c;
  }
  return out + '"';
}
struct Context {
  int64_t id;
  Context* previous;
  std::map<std::string, Cost> costs;
  std::map<std::string, int64_t> counters;
  std::map<std::string, std::string> dictionaries;
  explicit Context(int64_t request) : id(request), previous(active) { active = this; }
  ~Context() {
    active = previous;
#if defined(__ANDROID__)
    for (const auto& [phase, c] : costs) {
      __android_log_print(ANDROID_LOG_DEBUG, "DictProfileNative",
          "{\"id\":%lld,\"pid\":%d,\"phase\":\"%s\",\"calls\":%lld,\"wall_ns\":%lld,\"cpu_ns\":%lld,\"minor_faults\":%lld,\"major_faults\":%lld}",
          (long long)id, getpid(), phase.c_str(), (long long)c.calls, (long long)c.wall,
          (long long)c.cpu, (long long)c.minor, (long long)c.major);
    }
    for (const auto& [name, value] : counters) {
      __android_log_print(ANDROID_LOG_DEBUG, "DictProfileNative",
          "{\"id\":%lld,\"pid\":%d,\"counter\":\"%s\",\"value\":%lld}",
          (long long)id, getpid(), name.c_str(), (long long)value);
    }
    for (const auto& [phase, name] : dictionaries) {
      __android_log_print(ANDROID_LOG_DEBUG, "DictProfileNative",
          "{\"id\":%lld,\"pid\":%d,\"dictionary_phase\":%s,\"dictionary_name\":%s}",
          (long long)id, getpid(), quote(phase).c_str(), quote(name).c_str());
    }
#endif
  }
};
struct Scope {
  Context* context = active;
  std::string phase;
  std::optional<Sample> start;
  bool tracing = false;
  explicit Scope(std::string_view name) {
    if (!context) return;
    start.emplace();
    phase = name;
#if defined(__ANDROID__)
    tracing = ATrace_isEnabled();
    if (tracing) {
      char label[128];
      snprintf(label, sizeof(label), "DP/%lld/%s", (long long)context->id, phase.c_str());
      ATrace_beginSection(label);
    }
#endif
  }
  ~Scope() {
    if (!context) return;
    Sample end;
#if defined(__ANDROID__)
    if (tracing) ATrace_endSection();
#endif
    auto& cost = context->costs[phase];
    ++cost.calls;
    cost.wall += end.wall - start->wall;
    cost.cpu += end.cpu - start->cpu;
    cost.minor += end.minor - start->minor;
    cost.major += end.major - start->major;
  }
};
inline void count(const char* name, int64_t value = 1) {
  if (active) active->counters[name] += value;
}
inline void dictionary(const char* kind, size_t slot, const std::string& name) {
  if (active) active->dictionaries.try_emplace(std::string(kind) + '/' + std::to_string(slot), name);
}
template<class Work> auto measure(const char* name, Work&& work) {
  if (!active) return std::forward<Work>(work)();
  Scope scope(name);
  return std::forward<Work>(work)();
}
}
