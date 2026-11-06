#include "document_layout.h"
#include "css_numeric.hpp"
#include "resolved_style.hpp"
#include <array>
#include <cstdio>
#include <limits>
#include <memory>
#include <stdexcept>
#include <span>
#include <string>
#include <vector>

namespace {
constexpr uint32_t none=UINT32_MAX;
struct Edges { float top=0,right=0,bottom=0,left=0; };
struct Bounds { float min=0,max=0; };
using Flow=std::vector<dc_flow_input>;
}
struct dc_layout_document {
    dc_text_engine engine{};
    std::vector<dc_text_measurement> measured;
    std::vector<dc_paragraph_placement> paragraphs;
    std::vector<dc_cell_placement> cells;
    std::vector<dc_source_geometry> sources;
    std::vector<dc_block_placement> blocks;
    uint64_t wraps=0,unresolved=0;
    ~dc_layout_document() { for(const auto& m:measured) engine.release(engine.context,m.handle); }
};
namespace {
class Placement {
    dc_prepared_view v;
    const dictionary_core::ResolvedStyles &resolved;
    dc_layout_document& out;
    dc_error* error;
    std::vector<Flow> source_flows,cell_flows;
    std::vector<Bounds> cell_bounds,table_bounds;
    std::vector<uint8_t> measured_cells,measured_tables;
    std::vector<std::vector<uint32_t>> table_rows,row_cells;
    struct GridCell { uint32_t cell,row,column,rows,columns; };
    struct Grid { std::vector<GridCell> cells; std::vector<float> fractions; uint32_t rows=0,columns=0; };
    std::vector<Grid> grids;
    std::string_view property(uint32_t style,std::string_view name) const {
        return resolved.property(style,name);
    }
    float length(std::string_view value,uint32_t style,float basis,float fallback=0) {
        if(value.empty() || value=="auto" || value=="normal")return fallback;
        auto n=resolved.length(style,value,basis);
        if(n)return *n;
        ++out.unresolved;return fallback;
    }
    Edges edges(uint32_t style,std::string_view prefix,float basis) {
        std::array<float,4> values{}; size_t count=0;
        auto shorthand=property(style,prefix);
        size_t begin=0;
        while(begin<shorthand.size() && count<4) {
            while(begin<shorthand.size() && shorthand[begin]==' ')++begin;
            size_t end=begin;unsigned depth=0;
            for(;end<shorthand.size();++end){if(shorthand[end]=='(')++depth;if(shorthand[end]==')' && depth)--depth;if(shorthand[end]==' ' && !depth)break;}
            if(end>begin)values[count++]=length(shorthand.substr(begin,end-begin),style,basis);
            begin=end+1;
        }
        Edges e;
        if(count){e.top=values[0];e.right=count>1?values[1]:values[0];e.bottom=count>2?values[2]:values[0];e.left=count>3?values[3]:e.right;}
        const std::string name(prefix);
        e.top=length(property(style,name+"-top"),style,basis,e.top);
        e.right=length(property(style,name+"-right"),style,basis,e.right);
        e.bottom=length(property(style,name+"-bottom"),style,basis,e.bottom);
        e.left=length(property(style,name+"-left"),style,basis,e.left);
        e.top=length(property(style,name+"-block-start"),style,basis,e.top);
        e.bottom=length(property(style,name+"-block-end"),style,basis,e.bottom);
        e.left=length(property(style,name+"-inline-start"),style,basis,e.left);
        e.right=length(property(style,name+"-inline-end"),style,basis,e.right);
        return e;
    }
    static float collapse(float a,float b) {return std::max(0.f,std::max(a,b))+std::min(0.f,std::min(a,b));}
    float border(uint32_t style) {
        if(property(style,"border-style")=="none" || property(style,"border")=="none")return 0;
        auto width=property(style,"border-width");
        if(!width.empty())return length(width,style,0);
        auto value=property(style,"border");size_t at=0;
        while(at<value.size()) {
            while(at<value.size() && value[at]==' ')++at;
            auto end=value.find(' ',at);if(end==value.npos)end=value.size();
            auto token=value.substr(at,end-at);
            if(token=="thin")return 1;if(token=="medium")return 3;if(token=="thick")return 5;
            const float root=v.base_font_size_px>0?v.base_font_size_px:16.f;
            if(auto n=dictionary_core::CssNumeric(token,v.styles[style].font_size_px,root,0).evaluate(false))return std::max(0.f,*n);
            at=end+1;
        }
        return 0;
    }
    Edges border_edges(uint32_t style) {
        const float common=border(style);
        return {length(property(style,"border-top-width"),style,0,common),length(property(style,"border-right-width"),style,0,common),
            length(property(style,"border-bottom-width"),style,0,common),length(property(style,"border-left-width"),style,0,common)};
    }
    Edges cell_padding(uint32_t cell,float width) {
        const auto style=v.cells[cell].style;
        auto result=edges(style,"padding",width);
        const auto table=v.rows[v.cells[cell].row].table;
        const auto table_style=v.tables[table].style;
        const auto b=border_edges(style);
        const float factor=property(table_style,"display")=="grid" || property(table_style,"border-collapse")!="collapse"?1.f:.5f;
        result.top+=b.top*factor;result.right+=b.right*factor;result.bottom+=b.bottom*factor;result.left+=b.left*factor;
        return result;
    }
    Bounds intrinsic_flow(std::span<const dc_flow_input> flow,unsigned depth) {
        if(depth>128)throw std::runtime_error("Native layout nesting limit");
        Bounds result;float inset=0;std::vector<float> insets;
        for(const auto& f:flow) {
            if(f.kind==2) {
                const auto padding=edges(f.index,"padding",0),margin=edges(f.index,"margin",0),b=border_edges(f.index);
                const float added=padding.left+padding.right+margin.left+margin.right+b.left+b.right;
                insets.push_back(added);inset+=added;continue;
            }
            if(f.kind==3) {if(!insets.empty()){inset-=insets.back();insets.pop_back();}continue;}
            Bounds b;
            if(f.kind==0)b={out.measured.at(f.index).min_width,out.measured.at(f.index).max_width};
            else if(f.kind==1)b=intrinsic_table(f.index,depth+1);
            b.min+=inset;b.max+=inset;
            result.min=std::max(result.min,b.min);result.max=std::max(result.max,b.max);
        }
        return result;
    }
    Bounds intrinsic_cell(uint32_t id,unsigned depth) {
        if(measured_cells[id])return cell_bounds[id];
        auto b=intrinsic_flow(cell_flows[id],depth+1);
        const auto padding=cell_padding(id,0);
        b.min+=padding.left+padding.right;b.max+=padding.left+padding.right;
        measured_cells[id]=1;return cell_bounds[id]=b;
    }
    void column_bounds(uint32_t id,std::vector<float>& minimum,std::vector<float>& maximum,unsigned depth) {
        const auto& g=grids[id];minimum.assign(g.columns,0);maximum.assign(g.columns,0);
        for(const auto& c:g.cells) {
            if(c.columns!=1)continue;
            const auto b=intrinsic_cell(c.cell,depth);
            minimum[c.column]=std::max(minimum[c.column],b.min);maximum[c.column]=std::max(maximum[c.column],b.max);
        }
        for(const auto& c:g.cells) {
            if(c.columns==1)continue;
            const auto b=intrinsic_cell(c.cell,depth);
            float lo=0,hi=0;for(uint32_t j=0;j<c.columns;++j){lo+=minimum[c.column+j];hi+=maximum[c.column+j];}
            const float add_lo=std::max(0.f,b.min-lo)/c.columns,add_hi=std::max(0.f,b.max-hi)/c.columns;
            for(uint32_t j=0;j<c.columns;++j){minimum[c.column+j]+=add_lo;maximum[c.column+j]=std::max(minimum[c.column+j],maximum[c.column+j]+add_hi);}
        }
    }
    Bounds intrinsic_table(uint32_t id,unsigned depth) {
        if(measured_tables[id])return table_bounds[id];
        std::vector<float> lo,hi;column_bounds(id,lo,hi,depth+1);Bounds b;
        for(auto x:lo)b.min+=x;for(auto x:hi)b.max+=x;
        measured_tables[id]=1;return table_bounds[id]=b;
    }
    float place_flow(const Flow& flow,uint32_t source,float x,float y,float width,unsigned depth,bool crop_outer=false) {
        if(depth>128)throw std::runtime_error("Native layout nesting limit");
        struct Frame{float x,width,bottom_padding,bottom_margin,content_x;uint32_t block,style;};std::vector<Frame> stack;
        const float top=y;float pending_margin=0;bool at_start=crop_outer;
        for(size_t at=0;at<flow.size();++at) {
            const auto& f=flow[at];
            if(f.kind==2) {
                const auto margin=edges(f.index,"margin",width);auto padding=edges(f.index,"padding",width);
                const auto b=border_edges(f.index);
                padding.top+=b.top;padding.right+=b.right;padding.bottom+=b.bottom;padding.left+=b.left;
                pending_margin=collapse(pending_margin,margin.top);
                float box_width=std::max(1.f,width-margin.left-margin.right);
                if(property(f.index,"display")=="inline-block" || property(f.index,"width")=="fit-content") {
                    size_t end=at+1;unsigned nesting=1;
                    for(;end<flow.size();++end) {
                        if(flow[end].kind==2)++nesting;
                        else if(flow[end].kind==3 && !--nesting)break;
                    }
                    const auto bounds=intrinsic_flow(std::span(flow).subspan(at+1,end-at-1),depth+1);
                    const float horizontal=padding.left+padding.right;
                    box_width=std::min(std::max(bounds.min+horizontal,box_width),bounds.max+horizontal);
                }
                const auto block=static_cast<uint32_t>(out.blocks.size());
                out.blocks.push_back({f.index,source,f.cell,x+margin.left,y+(at_start?0:pending_margin),box_width,0});
                if(padding.top!=0) {if(!at_start)y+=pending_margin;y+=padding.top;pending_margin=0;at_start=false;}
                stack.push_back({x,width,padding.bottom,margin.bottom,x+margin.left+padding.left,block,f.index});
                x+=margin.left+padding.left;width=std::max(1.f,box_width-padding.left-padding.right);
            } else if(f.kind==3) {
                if(stack.empty())throw std::runtime_error("Unbalanced native block flow");
                const auto frame=stack.back();stack.pop_back();
                if(frame.bottom_padding!=0) {
                    y+=pending_margin+frame.bottom_padding;
                    pending_margin=frame.bottom_margin;
                    at_start=false;
                } else {
                    pending_margin=collapse(pending_margin,frame.bottom_margin);
                }
                out.blocks[frame.block].height=std::max(0.f,y-out.blocks[frame.block].y);
                x=frame.x;width=frame.width;
            } else {
                if(!at_start)y+=pending_margin;pending_margin=0;at_start=false;
                if(f.kind==0) {
                    dc_text_geometry geometry{};const auto& measured=out.measured.at(f.index);
                    if(out.engine.wrap(out.engine.context,measured.handle,width,&geometry,error))throw std::runtime_error(error?error->message:"Text wrapping failed");
                    if(!std::isfinite(geometry.height) || geometry.height<0)throw std::runtime_error("Invalid text geometry");
                    float marker_x=x;
                    if(v.paragraphs[f.index].marker_bytes)
                        for(auto frame=stack.rbegin();frame!=stack.rend();++frame)
                            if(frame->style==v.paragraphs[f.index].marker_style){marker_x=frame->content_x;break;}
                    ++out.wraps;out.paragraphs.push_back({f.index,source,measured.handle,x,y,width,geometry.height,geometry.lines,marker_x});y+=geometry.height;
                } else if(f.kind==1) {
                    const auto style=v.tables[f.index].style;
                    const auto margin=edges(style,"margin",width);
                    y+=margin.top;
                    y+=place_table(f.index,source,x+margin.left,y,std::max(1.f,width-margin.left-margin.right),depth+1);
                    pending_margin=margin.bottom;
                }
            }
        }
        if(!stack.empty())throw std::runtime_error("Unclosed native block flow");
        return y+(crop_outer?0:pending_margin)-top;
    }
    float place_table(uint32_t id,uint32_t source,float x,float y,float width,unsigned depth) {
        const auto& grid=grids[id];if(!grid.columns || !grid.rows)return 0;
        std::vector<float> lo,hi;column_bounds(id,lo,hi,depth);
        float min=0,max=0;for(auto a:lo)min+=a;for(auto a:hi)max+=a;
        const auto style=v.tables[id].style;
        const bool fractional=!grid.fractions.empty();
        // Grid padding belongs outside its tracks, unlike cell padding.
        auto padding=fractional?edges(style,"padding",width):Edges{};
        const auto border=fractional?border_edges(style):Edges{};
        padding.top+=border.top;padding.right+=border.right;
        padding.bottom+=border.bottom;padding.left+=border.left;
        const auto specified=property(style,"width");
        const float target=fractional?std::max(1.f,length(specified,style,width,width)-padding.left-padding.right):
            std::max(min,length(specified,style,width,std::min(width,max)));
        x+=padding.left;y+=padding.top;
        const float fraction=max>min?std::clamp((target-min)/(max-min),0.f,1.f):0;
        std::vector<float> columns(grid.columns+1,0),row_heights(grid.rows,0),cell_heights(grid.cells.size(),0);
        float total_fraction=0;for(auto f:grid.fractions)total_fraction+=f;
        for(uint32_t i=0;i<grid.columns;++i)columns[i+1]=columns[i]+(fractional?
            target*grid.fractions[i]/total_fraction:lo[i]+(hi[i]-lo[i])*fraction);
        struct Range{size_t paragraphs,cells,blocks;};std::vector<Range> starts;
        for(const auto& c:grid.cells) {
            starts.push_back({out.paragraphs.size(),out.cells.size(),out.blocks.size()});
            const float cell_width=columns[c.column+c.columns]-columns[c.column];
            const auto padding=cell_padding(c.cell,cell_width);
            const float height=padding.top+place_flow(cell_flows[c.cell],source,x+columns[c.column]+padding.left,y+padding.top,
                std::max(1.f,cell_width-padding.left-padding.right),depth+1)+padding.bottom;
            cell_heights[starts.size()-1]=height;
            if(c.rows==1)row_heights[c.row]=std::max(row_heights[c.row],height);
        }
        for(size_t i=0;i<grid.cells.size();++i) {
            const auto& c=grid.cells[i];float available=0;for(uint32_t j=0;j<c.rows;++j)available+=row_heights[c.row+j];
            const float extra=std::max(0.f,cell_heights[i]-available)/c.rows;
            for(uint32_t j=0;j<c.rows;++j)row_heights[c.row+j]+=extra;
        }
        std::vector<float> rows(grid.rows+1,0);for(uint32_t i=0;i<grid.rows;++i)rows[i+1]=rows[i]+row_heights[i];
        const auto last_paragraph=out.paragraphs.size(),last_cell=out.cells.size(),last_block=out.blocks.size();
        for(size_t i=0;i<grid.cells.size();++i) {
            const auto& c=grid.cells[i];const float height=rows[c.row+c.rows]-rows[c.row];
            const auto align=property(v.cells[c.cell].style,"vertical-align");
            const float spare=std::max(0.f,height-cell_heights[i]);const float shift=rows[c.row]+(align=="middle"?spare/2:align=="bottom"?spare:0);
            const auto p_end=i+1<starts.size()?starts[i+1].paragraphs:last_paragraph;
            const auto c_end=i+1<starts.size()?starts[i+1].cells:last_cell;
            const auto b_end=i+1<starts.size()?starts[i+1].blocks:last_block;
            for(auto p=starts[i].paragraphs;p<p_end;++p)out.paragraphs[p].y+=shift;
            for(auto k=starts[i].cells;k<c_end;++k)out.cells[k].y+=shift;
            for(auto k=starts[i].blocks;k<b_end;++k)out.blocks[k].y+=shift;
        }
        for(const auto& c:grid.cells)out.cells.push_back({c.cell,x+columns[c.column],y+rows[c.row],columns[c.column+c.columns]-columns[c.column],rows[c.row+c.rows]-rows[c.row]});
        return rows.back()+padding.top+padding.bottom;
    }
public:
    Placement(dc_prepared_view view,const dictionary_core::ResolvedStyles &styles,dc_layout_document& output,dc_error* e,size_t sources):v(view),resolved(styles),out(output),error(e),source_flows(sources),cell_flows(v.cell_count),
        cell_bounds(v.cell_count),table_bounds(v.table_count),measured_cells(v.cell_count),measured_tables(v.table_count),table_rows(v.table_count),row_cells(v.row_count),grids(v.table_count) {
        for(size_t i=0;i<v.flow_count;++i){const auto f=v.flow[i];if(f.cell==none)source_flows.at(f.source).push_back(f);else cell_flows.at(f.cell).push_back(f);}
        for(uint32_t i=0;i<v.row_count;++i)table_rows.at(v.rows[i].table).push_back(i);
        for(uint32_t i=0;i<v.cell_count;++i)row_cells.at(v.cells[i].row).push_back(i);
        for(uint32_t t=0;t<v.table_count;++t) {
            auto& g=grids[t];std::vector<uint32_t> occupied;uint32_t r=0;
            for(auto row:table_rows[t]) {
                uint32_t column=0;
                for(auto c:row_cells[row]) {
                    const auto& cell=v.cells[c];const uint32_t span=std::max(1u,cell.column_span);
                    for(;;++column) {
                        if(column+span>4096)throw std::runtime_error("Table column limit");
                        if(occupied.size()<column+span)occupied.resize(column+span,0);
                        bool free=true;for(uint32_t j=0;j<span;++j)if(occupied[column+j]>r)free=false;
                        if(free)break;
                    }
                    const uint32_t rows=std::max(1u,cell.row_span);
                    for(uint32_t j=0;j<span;++j)occupied[column+j]=r+rows;
                    g.cells.push_back({c,r,column,rows,span});g.columns=std::max(g.columns,column+span);g.rows=std::max(g.rows,r+rows);column+=span;
                }
                ++r;
            }
            if(property(v.tables[t].style,"display")=="grid") {
                const auto tracks=property(v.tables[t].style,"grid-template-columns");
                std::vector<float> fractions;size_t at=0;
                while(at<tracks.size()) {
                    while(at<tracks.size() && tracks[at]==' ')++at;
                    auto end=tracks.find(' ',at);if(end==tracks.npos)end=tracks.size();
                    const auto token=tracks.substr(at,end-at);
                    if(!token.ends_with("fr")){fractions.clear();break;}
                    auto n=dictionary_core::CssNumeric(token.substr(0,token.size()-2),16,16,0).evaluate();
                    if(!n || *n<=0){fractions.clear();break;}
                    fractions.push_back(*n);at=end+1;
                }
                if(fractions.size()>=g.columns) {
                    g.columns=static_cast<uint32_t>(fractions.size());g.fractions=std::move(fractions);
                }
            }
        }
    }
    void run(const float* widths,size_t count) {
        out.measured.reserve(v.paragraph_count);
        for(uint32_t p=0;p<v.paragraph_count;++p) {
            dc_text_measurement m{};
            if(out.engine.prepare(out.engine.context,&v,p,v.paragraphs[p].cell!=none,&m,error))throw std::runtime_error(error?error->message:"Text preparation failed");
            out.measured.push_back(m);
            if(!std::isfinite(m.min_width) || !std::isfinite(m.max_width) || m.min_width<0 || m.max_width<m.min_width)throw std::runtime_error("Invalid text widths");
        }
        for(uint32_t s=0;s<count;++s) {
            if(!std::isfinite(widths[s]) || widths[s]<=0)throw std::invalid_argument("Invalid source width");
            out.sources.push_back({widths[s],place_flow(source_flows[s],s,0,0,widths[s],0,true)});
        }
    }
};
}
int dc_layout(const dc_prepared_document* input,const dc_text_engine* engine,const float* widths,size_t count,dc_layout_document** output,dc_error* error) {
    if(output)*output=nullptr;if(error)*error={};
    try {
        if(!input || !engine || !engine->prepare || !engine->wrap || !engine->release || !output || (!widths && count))throw std::invalid_argument("Invalid layout arguments");
        if(count!=dc_prepared_document_view(input).source_count)throw std::invalid_argument("Layout source count mismatch");
        auto result=std::make_unique<dc_layout_document>();result->engine=*engine;
        Placement(dc_prepared_document_view(input),dictionary_core::resolved_styles(input),*result,error,count).run(widths,count);
        *output=result.release();return 0;
    } catch(const std::exception& e){if(error)std::snprintf(error->message,sizeof(error->message),"%s",e.what());return 1;}
    catch(...){if(error)std::snprintf(error->message,sizeof(error->message),"Unknown native layout failure");return 1;}
}
dc_layout_view dc_layout_document_view(const dc_layout_document* d) {
    if(!d)return {};
    return {d->paragraphs.data(),d->paragraphs.size(),d->cells.data(),d->cells.size(),d->sources.data(),d->sources.size(),d->measured.size(),d->wraps,d->unresolved,d->blocks.data(),d->blocks.size()};
}
void dc_layout_document_destroy(dc_layout_document* d){delete d;}
