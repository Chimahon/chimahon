#define NOMINMAX
#include "windows_text_engine.hpp"
#include <windows.h>
#include <dwrite_3.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <limits>
#include <map>
#include <stdexcept>
#include <string_view>
#include <vector>

namespace {
template<class T> class Com {
    T* pointer=nullptr;
public:
    Com()=default;Com(const Com&)=delete;Com& operator=(const Com&)=delete;
    Com(Com&& other) noexcept:pointer(other.pointer){other.pointer=nullptr;}
    Com& operator=(Com&& other) noexcept {if(this!=&other){if(pointer)pointer->Release();pointer=other.pointer;other.pointer=nullptr;}return *this;}
    ~Com(){if(pointer)pointer->Release();}
    T** put(){if(pointer)pointer->Release();pointer=nullptr;return &pointer;}
    T* get()const{return pointer;}T* operator->()const{return pointer;}
};
void checked(HRESULT hr,const char* operation) {if(FAILED(hr))throw std::runtime_error(std::string(operation)+": HRESULT "+std::to_string(static_cast<unsigned long>(hr)));}
std::wstring utf16(std::string_view text) {
    if(text.empty())return {};
    if(text.size()>INT_MAX)throw std::runtime_error("Text exceeds Windows adapter limit");
    const auto size=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),static_cast<int>(text.size()),nullptr,0);
    if(!size)throw std::runtime_error("Invalid UTF-8 text");
    std::wstring result(size,0);
    if(!MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,text.data(),static_cast<int>(text.size()),result.data(),size))throw std::runtime_error("UTF-16 conversion failed");
    return result;
}
std::string_view property(const dc_prepared_view& v,uint32_t style,std::string_view name) {
    const auto& s=v.styles[style];
    for(uint32_t i=0;i<s.property_count;++i) {
        const auto& p=v.properties[s.first_property+i];
        if(std::string_view(v.text+p.name_offset,p.name_bytes)==name)return {v.text+p.value_offset,p.value_bytes};
    }
    return {};
}
struct Range {DWRITE_TEXT_RANGE text;uint32_t style;};
struct Text {std::wstring value;std::vector<Range> ranges;};
Text text_runs(const dc_prepared_view& v,uint32_t first,uint32_t count) {
    Text text;
    for(uint32_t i=first;i<first+count;++i) {
        const auto& r=v.runs[i];auto value=utf16({v.text+r.text_offset,r.text_bytes});
        text.ranges.push_back({{static_cast<uint32_t>(text.value.size()),static_cast<uint32_t>(value.size())},r.style});
        text.value+=value;
    }
    return text;
}
class RubyObject final:public IDWriteInlineObject {
    ULONG references=1;
    Com<IDWriteTextLayout> base,reading;
    DWRITE_INLINE_OBJECT_METRICS metrics{};
    float base_x=0,reading_x=0,base_y=0;
public:
    RubyObject(Com<IDWriteTextLayout> b,Com<IDWriteTextLayout> r):base(std::move(b)),reading(std::move(r)) {
        DWRITE_TEXT_METRICS bm{},rm{};checked(base->GetMetrics(&bm),"Ruby base metrics");checked(reading->GetMetrics(&rm),"Ruby reading metrics");
        UINT32 count=0;base->GetLineMetrics(nullptr,0,&count);
        std::vector<DWRITE_LINE_METRICS> lines(count);checked(base->GetLineMetrics(lines.data(),count,&count),"Ruby baseline");
        metrics.width=std::max(bm.widthIncludingTrailingWhitespace,rm.widthIncludingTrailingWhitespace);
        metrics.height=bm.height+rm.height;metrics.baseline=rm.height+(lines.empty()?bm.height:lines[0].baseline);
        base_y=rm.height;base_x=(metrics.width-bm.widthIncludingTrailingWhitespace)/2;reading_x=(metrics.width-rm.widthIncludingTrailingWhitespace)/2;
    }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** out)override {
        if(!out)return E_POINTER;*out=nullptr;
        if(id==__uuidof(IUnknown)||id==__uuidof(IDWriteInlineObject)){*out=static_cast<IDWriteInlineObject*>(this);AddRef();return S_OK;}return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef()override{return ++references;}
    ULONG STDMETHODCALLTYPE Release()override{auto n=--references;if(!n)delete this;return n;}
    HRESULT STDMETHODCALLTYPE Draw(void* context,IDWriteTextRenderer* renderer,FLOAT x,FLOAT y,BOOL,BOOL,IUnknown*)override {
        auto hr=base->Draw(context,renderer,x+base_x,y+base_y);if(FAILED(hr))return hr;
        return reading->Draw(context,renderer,x+reading_x,y);
    }
    HRESULT STDMETHODCALLTYPE GetMetrics(DWRITE_INLINE_OBJECT_METRICS* out)override{if(!out)return E_POINTER;*out=metrics;return S_OK;}
    HRESULT STDMETHODCALLTYPE GetOverhangMetrics(DWRITE_OVERHANG_METRICS* out)override{if(!out)return E_POINTER;*out={};return S_OK;}
    HRESULT STDMETHODCALLTYPE GetBreakConditions(DWRITE_BREAK_CONDITION* before,DWRITE_BREAK_CONDITION* after)override {
        if(!before||!after)return E_POINTER;*before=*after=DWRITE_BREAK_CONDITION_NEUTRAL;return S_OK;
    }
};
struct Paragraph {Com<IDWriteTextLayout> layout,marker;};
int fail(dc_error* error,const char* message) {if(error)std::snprintf(error->message,sizeof(error->message),"%s",message);return 1;}
}
struct WindowsTextEngine::State {
    Com<IDWriteFactory3> factory;
    Com<IDWriteFontCollection1> fonts;
    std::wstring family;
    std::map<float,Com<IDWriteTextFormat>> formats;
    std::map<std::pair<uint32_t,bool>,DWRITE_FONT_METRICS> drawing_metrics;
    std::map<std::pair<uint32_t,bool>,std::pair<float,float>> drawing_em_ratios;
    bool force_intrinsic=false;
    struct FontFacts { float size; DWRITE_FONT_WEIGHT weight; DWRITE_FONT_STYLE slant; DWRITE_TEXT_ALIGNMENT alignment; bool rtl,nowrap; };
    std::vector<FontFacts> facts;
    void resolve_facts(const dc_prepared_view& v) {
        facts.clear();facts.reserve(v.style_count);
        for(uint32_t style=0;style<v.style_count;++style) {
            const auto weight=property(v,style,"font-weight");
            unsigned n=400;
            if(weight=="bold"||weight=="bolder")n=700;
            else if(!weight.empty()){try{n=std::stoul(std::string(weight));}catch(...){n=400;}}
            const auto slant=property(v,style,"font-style"),align=property(v,style,"text-align"),white=property(v,style,"white-space");
            facts.push_back({std::max(.01f,v.styles[style].font_size_px),static_cast<DWRITE_FONT_WEIGHT>(std::clamp(n,1u,999u)),
                slant=="italic"?DWRITE_FONT_STYLE_ITALIC:slant=="oblique"?DWRITE_FONT_STYLE_OBLIQUE:DWRITE_FONT_STYLE_NORMAL,
                align=="center"?DWRITE_TEXT_ALIGNMENT_CENTER:align=="right"||align=="end"?DWRITE_TEXT_ALIGNMENT_TRAILING:align=="justify"?DWRITE_TEXT_ALIGNMENT_JUSTIFIED:DWRITE_TEXT_ALIGNMENT_LEADING,
                property(v,style,"direction")=="rtl",white=="pre"||white=="nowrap"});
        }
    }
    explicit State(const std::string& file) {
        checked(DWriteCreateFactory(DWRITE_FACTORY_TYPE_ISOLATED,__uuidof(IDWriteFactory3),reinterpret_cast<IUnknown**>(factory.put())),"Create DirectWrite factory");
        Com<IDWriteFontFile> font;const auto path=utf16(file);
        checked(factory->CreateFontFileReference(path.c_str(),nullptr,font.put()),"Open benchmark font");
        BOOL supported=FALSE;DWRITE_FONT_FILE_TYPE type{};DWRITE_FONT_FACE_TYPE face{};UINT32 count=0;
        checked(font->Analyze(&supported,&type,&face,&count),"Analyze benchmark font");
        if(!supported||!count)throw std::runtime_error("Unsupported benchmark font");
        Com<IDWriteFontSetBuilder> builder;checked(factory->CreateFontSetBuilder(builder.put()),"Create font set builder");
        for(UINT32 i=0;i<count;++i){Com<IDWriteFontFaceReference> ref;checked(factory->CreateFontFaceReference(path.c_str(),nullptr,i,DWRITE_FONT_SIMULATIONS_NONE,ref.put()),"Create font face");checked(builder->AddFontFaceReference(ref.get()),"Add font face");}
        Com<IDWriteFontSet> set;checked(builder->CreateFontSet(set.put()),"Create font set");
        checked(factory->CreateFontCollectionFromFontSet(set.get(),fonts.put()),"Create font collection");
        Com<IDWriteFontFamily1> font_family;checked(fonts->GetFontFamily(0,font_family.put()),"Read font family");
        Com<IDWriteLocalizedStrings> names;checked(font_family->GetFamilyNames(names.put()),"Read font name");
        UINT32 length=0;checked(names->GetStringLength(0,&length),"Read font name length");family.resize(length+1);
        checked(names->GetString(0,family.data(),length+1),"Read font name");family.resize(length);
    }
    IDWriteTextFormat* format(float size) {
        size=std::max(.01f,size);
        auto [it,inserted]=formats.try_emplace(size);
        if(inserted)checked(factory->CreateTextFormat(family.c_str(),fonts.get(),DWRITE_FONT_WEIGHT_NORMAL,DWRITE_FONT_STYLE_NORMAL,DWRITE_FONT_STRETCH_NORMAL,size,L"ja-JP",it->second.put()),"Create font format");
        return it->second.get();
    }
    Com<IDWriteTextLayout> layout(const dc_prepared_view& v,const Text& text,uint32_t style,bool nowrap=false) {
        Com<IDWriteTextLayout> result;
        checked(factory->CreateTextLayout(text.value.data(),static_cast<UINT32>(text.value.size()),format(v.styles[style].font_size_px),100000,1000000,result.put()),"Create text layout");
        if(nowrap)checked(result->SetWordWrapping(DWRITE_WORD_WRAPPING_NO_WRAP),"Set ruby no-wrap");
        for(const auto& r:text.ranges) {
            const auto& font=facts[r.style];
            checked(result->SetFontSize(font.size,r.text),"Set font size");
            checked(result->SetFontWeight(font.weight,r.text),"Set font weight");
            checked(result->SetFontStyle(font.slant,r.text),"Set font style");
        }
        checked(result->SetTextAlignment(facts[style].alignment),"Set alignment");
        if(facts[style].rtl)checked(result->SetReadingDirection(DWRITE_READING_DIRECTION_RIGHT_TO_LEFT),"Set direction");
        if(facts[style].nowrap)checked(result->SetWordWrapping(DWRITE_WORD_WRAPPING_NO_WRAP),"Set white-space");
        return result;
    }
    static int prepare(void* context,const dc_prepared_view* input,uint32_t paragraph,uint32_t needs_intrinsic,dc_text_measurement* output,dc_error* error) noexcept {
        try {
            auto& self=*static_cast<State*>(context);const auto& v=*input;const auto& p=v.paragraphs[paragraph];Text text;
            if(paragraph==0)self.resolve_facts(v);
            std::vector<DWRITE_TEXT_RANGE> ranges(p.run_count);
            for(uint32_t i=0;i<p.run_count;++i) {
                const auto& r=v.runs[p.first_run+i];if(r.role==2)continue;
                auto value=utf16({v.text+r.text_offset,r.text_bytes});
                ranges[i]={static_cast<UINT32>(text.value.size()),static_cast<UINT32>(value.size())};text.ranges.push_back({ranges[i],r.style});text.value+=value;
            }
            auto result=std::make_unique<Paragraph>();result->layout=self.layout(v,text,p.style);
            if(p.marker_bytes) {
                Text marker;marker.value=utf16({v.text+p.marker_offset,p.marker_bytes});
                marker.ranges.push_back({{0,static_cast<uint32_t>(marker.value.size())},p.marker_style});
                result->marker=self.layout(v,marker,p.marker_style,true);
                DWRITE_TEXT_METRICS marker_metrics{};checked(result->marker->GetMetrics(&marker_metrics),"Measure list marker");
            }
            for(uint32_t i=0;i<p.run_count;++i) {
                const auto& run=v.runs[p.first_run+i];if(run.role!=1 || run.ruby==UINT32_MAX)continue;
                const auto& ruby=v.rubies[run.ruby];if(p.first_run+i!=ruby.first_base_run || !ruby.base_run_count || !ruby.reading_run_count)continue;
                if(ruby.first_base_run<p.first_run || ruby.first_base_run+ruby.base_run_count>p.first_run+p.run_count)throw std::runtime_error("Ruby crosses paragraph boundary");
                auto b=text_runs(v,ruby.first_base_run,ruby.base_run_count),r=text_runs(v,ruby.first_reading_run,ruby.reading_run_count);
                auto base=self.layout(v,b,v.runs[ruby.first_base_run].style,true),reading=self.layout(v,r,v.runs[ruby.first_reading_run].style,true);
                auto* object=new RubyObject(std::move(base),std::move(reading));
                const auto start=ranges[i].startPosition;const auto end=ranges[i+ruby.base_run_count-1];
                const auto status=result->layout->SetInlineObject(object,{start,end.startPosition+end.length-start});object->Release();checked(status,"Insert ruby object");
            }
            if(needs_intrinsic || self.force_intrinsic) {
                DWRITE_TEXT_METRICS metrics{};checked(result->layout->GetMetrics(&metrics),"Get natural paragraph metrics");
                float minimum=0;checked(result->layout->DetermineMinWidth(&minimum),"Get paragraph minimum width");
                output->min_width=minimum;output->max_width=std::max(minimum,metrics.widthIncludingTrailingWhitespace);
            }
            output->handle=reinterpret_cast<uintptr_t>(result.release());return 0;
        }catch(const std::exception& e){return fail(error,e.what());}catch(...){return fail(error,"Unknown Windows text preparation failure");}
    }
    static int wrap(void*,uint64_t handle,float width,dc_text_geometry* output,dc_error* error) noexcept {
        try{auto& p=*reinterpret_cast<Paragraph*>(static_cast<uintptr_t>(handle));checked(p.layout->SetMaxWidth(width),"Set paragraph width");
            DWRITE_TEXT_METRICS m{};checked(p.layout->GetMetrics(&m),"Wrap paragraph");*output={m.widthIncludingTrailingWhitespace,m.height,m.lineCount};return 0;
        }catch(const std::exception& e){return fail(error,e.what());}catch(...){return fail(error,"Unknown Windows wrapping failure");}
    }
    static void release(void*,uint64_t handle)noexcept {delete reinterpret_cast<Paragraph*>(static_cast<uintptr_t>(handle));}
};
WindowsTextEngine::WindowsTextEngine(const std::string& font):state(std::make_unique<State>(font)){}
WindowsTextEngine::~WindowsTextEngine()=default;
dc_text_engine WindowsTextEngine::callbacks(){return {state.get(),State::prepare,State::wrap,State::release};}
void WindowsTextEngine::force_intrinsic_for_verification(bool enabled){state->force_intrinsic=enabled;}
void WindowsTextEngine::measure_drawing_plan(dc_drawing_plan* plan) {
    auto v=dc_drawing_plan_view(plan);
    std::vector<dc_run_metrics> metrics(v.font_count);
    Com<IDWriteFontFamily1> family;checked(state->fonts->GetFontFamily(0,family.put()),"Drawing font family");
    for(size_t i=0;i<v.font_count;++i) {
        const auto& f=v.fonts[i];const auto key=std::make_pair(f.weight,(f.flags&1)!=0);
        auto found=state->drawing_metrics.find(key);
        if(found==state->drawing_metrics.end()) {
            Com<IDWriteFont> font;checked(family->GetFirstMatchingFont(static_cast<DWRITE_FONT_WEIGHT>(f.weight),DWRITE_FONT_STRETCH_NORMAL,
                (f.flags&1)?DWRITE_FONT_STYLE_ITALIC:DWRITE_FONT_STYLE_NORMAL,font.put()),"Drawing font metrics");
            DWRITE_FONT_METRICS m{};font->GetMetrics(&m);found=state->drawing_metrics.emplace(key,m).first;
            float ascent=m.ascent,descent=m.descent;
            Com<IDWriteFontFace> face;checked(font->CreateFontFace(face.put()),"Drawing font face");
            const void* table=nullptr;UINT32 size=0;void* context=nullptr;BOOL exists=FALSE;
            checked(face->TryGetFontTable(DWRITE_MAKE_OPENTYPE_TAG('O','S','/','2'),&table,&size,&context,&exists),"Drawing typo metrics");
            if(exists && size>=72) {
                const auto* bytes=static_cast<const uint8_t*>(table);
                const auto a=static_cast<int16_t>((bytes[68]<<8)|bytes[69]);
                const auto d=-static_cast<int16_t>((bytes[70]<<8)|bytes[71]);
                if(a>=0 && d>=0 && a+d>0){ascent=a;descent=d;}
            }
            if(context)face->ReleaseFontTable(context);
            const auto sum=std::max(1.f,ascent+descent);
            state->drawing_em_ratios.emplace(key,std::make_pair(ascent/sum,descent/sum));
        }
        const auto& m=found->second;float scale=f.size/m.designUnitsPerEm;
        const auto ratio=state->drawing_em_ratios.at(key);
        metrics[i]={-m.ascent*scale,m.descent*scale,std::min(std::ceil(ratio.first*f.size),m.ascent*scale),
            std::min(std::ceil(ratio.second*f.size),m.descent*scale)};
    }
    size_t request=0;
    for(size_t c=0;c<v.context_count;++c) {
        const auto& context=v.contexts[c];if(!context.count)continue;
        while(request<v.request_count && v.requests[request].start<context.start)++request;
        size_t end=request;while(end<v.request_count && v.requests[end].start<context.start+context.count)++end;
        Com<IDWriteTextLayout> layout;
        const float size=request<end?v.fonts[v.requests[request].style].size:16;
        checked(state->factory->CreateTextLayout(reinterpret_cast<const wchar_t*>(v.text+context.start),context.count,
            state->format(size),1000000,1000000,layout.put()),"Drawing context shaping");
        for(size_t r=request;r<end;++r) {
            const auto& run=v.requests[r];const auto& f=v.fonts[run.style];
            DWRITE_TEXT_RANGE range{run.start-context.start,run.count};
            if(f.size!=size)checked(layout->SetFontSize(f.size,range),"Drawing run size");
            if(f.weight!=400)checked(layout->SetFontWeight(static_cast<DWRITE_FONT_WEIGHT>(f.weight),range),"Drawing run weight");
            if(f.flags&1)checked(layout->SetFontStyle(DWRITE_FONT_STYLE_ITALIC,range),"Drawing run style");
            v.metrics[r]=metrics[run.style];
        }
        UINT32 count=0;layout->GetClusterMetrics(nullptr,0,&count);std::vector<DWRITE_CLUSTER_METRICS> clusters(count);
        checked(layout->GetClusterMetrics(clusters.data(),count,&count),"Drawing cluster advances");
        uint32_t at=context.start;
        for(const auto& cluster:clusters) {
            if(at+cluster.length>context.start+context.count)throw std::runtime_error("Drawing cluster bounds");
            for(uint32_t n=0;n<cluster.length;++n){v.advances[at+n]=n?0:cluster.width;v.levels[at+n]=cluster.isRightToLeft?1:0;}
            if(cluster.length)v.breaks[at+cluster.length-1]=cluster.canWrapLineAfter||cluster.isNewline;
            at+=cluster.length;
        }
        request=end;
    }
    for(size_t r=0;r<v.request_count;++r)if(v.requests[r].count==0)v.metrics[r]=metrics[v.requests[r].style];
}
