#pragma once
#include <algorithm>
#include <charconv>
#include <cmath>
#include <optional>
#include <string_view>
#include <array>
#include <vector>

namespace dictionary_core {
struct CssLengthProgram {
    enum class Op { constant, percent, add, subtract, multiply, divide };
    struct Instruction { Op op; float value; };
    std::vector<Instruction> instructions;
    std::optional<float> evaluate(float basis) const {
        std::array<float, 64> stack{};
        size_t top = 0;
        for (const auto &i : instructions) {
            if (i.op == Op::constant || i.op == Op::percent) {
                if (top == stack.size()) return {};
                stack[top++] = i.op == Op::percent ? i.value * basis / 100.f : i.value;
            } else {
                if (top < 2) return {};
                const float right = stack[--top];
                auto &left = stack[top - 1];
                if (i.op == Op::add) left += right;
                else if (i.op == Op::subtract) left -= right;
                else if (i.op == Op::multiply) left *= right;
                else left /= right;
            }
        }
        if (top != 1 || !std::isfinite(stack[0])) return {};
        return stack[0];
    }
};
// Evaluates the numeric subset used by placement; unknown syntax is never a guessed zero.
class CssNumeric {
    struct Value { float value; bool length; };
    std::string_view input;
    size_t at = 0;
    float em, root, percent;
    bool valid = true;
    CssLengthProgram *program = nullptr;
    void emit(CssLengthProgram::Op op, float value = 0) {
        if (program) program->instructions.push_back({op, value});
    }
    void spaces() { while (at < input.size() && (input[at]==' ' || input[at]=='\n' || input[at]=='\t' || input[at]=='\r')) ++at; }
    bool take(char c) { spaces(); if(at<input.size() && input[at]==c){++at;return true;}return false; }
    Value atom(unsigned depth) {
        spaces();
        if(depth>32 || at==input.size()){valid=false;return {};}
        if(input.substr(at,5)=="calc(") {at+=5;auto v=sum(depth+1);if(!take(')'))valid=false;return v;}
        if(take('(')){auto v=sum(depth+1);if(!take(')'))valid=false;return v;}
        float sign=1;
        if(take('-'))sign=-1;else take('+');
        float value=0;
        const auto result=std::from_chars(input.data()+at,input.data()+input.size(),value);
        if(result.ec!=std::errc{}){valid=false;return {};}
        at=static_cast<size_t>(result.ptr-input.data()); value*=sign;
        const auto start=at;
        while(at<input.size() && ((input[at]>='a' && input[at]<='z') || input[at]=='%')) ++at;
        const auto unit=input.substr(start,at-start);
        if(unit.empty()) {emit(CssLengthProgram::Op::constant,value);return {value,false};}
        if(unit=="%") {emit(CssLengthProgram::Op::percent,value);return {value*percent/100.f,true};}
        if(unit=="em")value*=em;
        else if(unit=="rem")value*=root;
        else if(unit=="pt")value=value*96.f/72.f;
        else if(unit!="px"){valid=false;return {};}
        emit(CssLengthProgram::Op::constant,value);return {value,true};
    }
    Value product(unsigned depth) {
        auto left=atom(depth);
        while(valid) {
            spaces();if(at==input.size() || (input[at]!='*' && input[at]!='/'))break;
            const char op=input[at++];auto right=atom(depth);
            if(op=='*') {if(left.length && right.length)valid=false;left={left.value*right.value,left.length||right.length};}
            else {if(right.length || right.value==0)valid=false;else left.value/=right.value;}
            emit(op=='*'?CssLengthProgram::Op::multiply:CssLengthProgram::Op::divide);
        }
        return left;
    }
    Value sum(unsigned depth) {
        auto left=product(depth);
        while(valid) {
            spaces();if(at==input.size() || (input[at]!='+' && input[at]!='-'))break;
            const char op=input[at++];auto right=product(depth);
            if(left.length!=right.length)valid=false;
            left.value+=op=='+'?right.value:-right.value;
            emit(op=='+'?CssLengthProgram::Op::add:CssLengthProgram::Op::subtract);
        }
        return left;
    }
public:
    CssNumeric(std::string_view s,float font,float root_font,float basis):input(s),em(font),root(root_font),percent(basis){}
    std::optional<float> evaluate(bool allow_number=true) {
        const auto result=sum(0);spaces();
        if(!valid || at!=input.size() || !std::isfinite(result.value) || (!allow_number && !result.length && result.value!=0))return {};
        return result.value;
    }
    std::optional<CssLengthProgram> compile() {
        CssLengthProgram result;
        program = &result;
        const auto value = evaluate();
        program = nullptr;
        if (!value) return {};
        return result;
    }
};
}
