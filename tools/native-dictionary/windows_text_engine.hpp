#pragma once
#include "document_layout.h"
#include "drawing_plan.h"
#include <memory>
#include <string>
class WindowsTextEngine {
    struct State;
    std::unique_ptr<State> state;
public:
    explicit WindowsTextEngine(const std::string& font_file);
    ~WindowsTextEngine();
    dc_text_engine callbacks();
    void force_intrinsic_for_verification(bool enabled);
    void measure_drawing_plan(dc_drawing_plan* plan);
};
