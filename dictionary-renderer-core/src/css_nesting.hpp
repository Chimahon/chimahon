#pragma once
#include <lexbor/css/css.h>
#include <lexbor/css/syntax/tokenizer.h>
#include <memory>
#include <stdexcept>
#include <string>

namespace dictionary_core {
inline lxb_status_t css_append(const lxb_char_t* data,size_t size,void* output) {
    try { static_cast<std::string*>(output)->append(reinterpret_cast<const char*>(data),size);return LXB_STATUS_OK; }
    catch(...) { return LXB_STATUS_ERROR_MEMORY_ALLOCATION; }
}
inline void css_require(lxb_status_t status) {
    if(status!=LXB_STATUS_OK)throw std::runtime_error("CSS nesting normalization failed");
}
// Lexbor rejects generated pseudo-elements. Keep their position in the selector
// AST as an internal marker; the compiler matches the originating element.
inline std::string generated_selector(const std::string& selector) {
    auto* raw=lxb_css_syntax_tokenizer_create();if(!raw)throw std::bad_alloc();
    std::unique_ptr<lxb_css_syntax_tokenizer_t,decltype(&lxb_css_syntax_tokenizer_destroy)> tokens(raw,lxb_css_syntax_tokenizer_destroy);
    css_require(lxb_css_syntax_tokenizer_init(raw));
    lxb_css_syntax_tokenizer_buffer_set(raw,reinterpret_cast<const lxb_char_t*>(selector.data()),selector.size());
    std::string result;unsigned colons=0;
    for(;;) {
        auto* token=lxb_css_syntax_token(raw);if(!token)throw std::runtime_error("Generated selector tokenization failed");
        if(token->type==LXB_CSS_SYNTAX_TOKEN_COLON)++colons;
        else {
            const std::string_view name=token->type==LXB_CSS_SYNTAX_TOKEN_IDENT?
                std::string_view(reinterpret_cast<const char*>(token->types.ident.data),token->types.ident.length):std::string_view{};
            if((colons==1 || colons==2) && (name=="before" || name=="after"))result+="[data-dc-generated-"+std::string(name)+"]";
            else {
                result.append(colons,':');
                if(token->type!=LXB_CSS_SYNTAX_TOKEN__EOF)css_require(lxb_css_syntax_token_serialize(token,css_append,&result));
            }
            colons=0;
        }
        if(token->type==LXB_CSS_SYNTAX_TOKEN__EOF)break;
        lxb_css_syntax_token_consume(raw);
    }
    return result;
}
// Token boundaries distinguish the nesting selector from '&' inside a string or escaped identifier.
inline std::string nested_selector(const std::string& selector,const std::string& parent) {
    if(parent.empty())return selector;
    auto* raw=lxb_css_syntax_tokenizer_create();
    if(!raw)throw std::bad_alloc();
    std::unique_ptr<lxb_css_syntax_tokenizer_t,decltype(&lxb_css_syntax_tokenizer_destroy)> tokenizer(raw,lxb_css_syntax_tokenizer_destroy);
    css_require(lxb_css_syntax_tokenizer_init(raw));
    lxb_css_syntax_tokenizer_buffer_set(raw,reinterpret_cast<const lxb_char_t*>(selector.data()),selector.size());
    std::string output,branch;bool explicit_parent=false;unsigned depth=0;
    auto commit=[&]() {
        if(!output.empty())output+=", ";
        if(!explicit_parent)output+=":is("+parent+") ";
        output+=branch;branch.clear();explicit_parent=false;
    };
    for(;;) {
        auto* token=lxb_css_syntax_token(raw);
        if(!token)throw std::runtime_error("CSS nesting tokenization failed");
        if(token->type==LXB_CSS_SYNTAX_TOKEN__EOF)break;
        if(token->type==LXB_CSS_SYNTAX_TOKEN_COMMA && depth==0)commit();
        else {
            if(token->type==LXB_CSS_SYNTAX_TOKEN_DELIM && token->types.delim.character=='&') {
                branch+=":is("+parent+")";explicit_parent=true;
            } else css_require(lxb_css_syntax_token_serialize(token,css_append,&branch));
            if(token->type==LXB_CSS_SYNTAX_TOKEN_FUNCTION || token->type==LXB_CSS_SYNTAX_TOKEN_L_PARENTHESIS ||
                token->type==LXB_CSS_SYNTAX_TOKEN_LS_BRACKET)++depth;
            if((token->type==LXB_CSS_SYNTAX_TOKEN_R_PARENTHESIS || token->type==LXB_CSS_SYNTAX_TOKEN_RS_BRACKET) && depth)--depth;
        }
        lxb_css_syntax_token_consume(raw);
    }
    commit();return output;
}
inline bool has_nested_rules(lxb_css_rule_list_t* list) {
    for(auto* rule=list->first;rule;rule=rule->next)
        if((rule->type==LXB_CSS_RULE_STYLE && lxb_css_rule_style(rule)->child) ||
            rule->type==LXB_CSS_RULE_BAD_STYLE)return true;
    return false;
}
inline void flatten_css(lxb_css_rule_list_t* list,const std::string& parent,std::string& output,unsigned depth=0) {
    if(depth>128)throw std::length_error("CSS nesting limit");
    for(auto* rule=list->first;rule;rule=rule->next) {
        std::string selector;lxb_css_rule_declaration_list_t* declarations=nullptr;lxb_css_rule_list_t* child=nullptr;
        if(rule->type==LXB_CSS_RULE_STYLE) {
            auto* style=lxb_css_rule_style(rule);
            css_require(lxb_css_selector_serialize_list_chain(style->selector,css_append,&selector));
            declarations=style->declarations;child=style->child;
        } else if(rule->type==LXB_CSS_RULE_BAD_STYLE) {
            auto* style=lxb_css_rule_bad_style(rule);
            selector.assign(reinterpret_cast<const char*>(style->selectors.data),style->selectors.length);
            declarations=style->declarations;child=style->child;
        } else {
            css_require(lxb_css_rule_serialize(rule,css_append,&output));output+='\n';continue;
        }
        const auto resolved=generated_selector(nested_selector(selector,parent));
        if(declarations && declarations->first) {
            output+=resolved+"{";
            css_require(lxb_css_rule_declaration_list_serialize(declarations,css_append,&output));output+="}\n";
        }
        if(child)flatten_css(child,resolved,output,depth+1);
    }
}
}
