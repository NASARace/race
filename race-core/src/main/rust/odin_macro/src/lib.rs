/*
 * Copyright (c) 2023, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#![allow(unused)]
///! This crate provides procedural macros used throughout the ODIN project, namely
///!
///!    - [`define_algebraic_type`] and [`match_algebraic_type`]
///!    - [`define_actor_msg_type`] and [`match_actor_msg_type`] (the [`odin_actor`] specific versions)
///! 
///! Its main use case within ODIN is to support concise syntax for [`odin_actor::Actor`] implementation as in:
///! ```
///!     define_actor_msg_type! {
///!         MyActorMsg = A | B<std::vec::Vec<(u32,&'static str)>>
///!     }
///!     ...
///!     impl Actor<MyActorMsg> for MyActor {
///!         async fn receive (...) {
///!             match_actor_msg_type! { msg: MyActorMsg as
///!                  B<std::vec::Vec<(u32,&'static str)>> => cont! { println!("got a B: {:?}", msg) }
///!                  ...
///!             }
///!         }
///!     }
///! ```

extern crate proc_macro;

use proc_macro::TokenStream;
use proc_macro2::{
	Literal, Punct, Spacing, Span, TokenStream as TokenStream2, TokenTree
};
use quote::{format_ident, quote, ToTokens, TokenStreamExt};
use syn::{ 
	self, parse::{Lookahead1, Parse, ParseStream, Result}, 
    parse_macro_input, punctuated::{Punctuated}, visit::{self, Visit}, 
    token::{self, Mut, Where, Colon, Gt, Lt, Comma, Paren, PathSep, Use, For, In}, 
    Attribute, Block, Expr, ExprLit, ExprCall, ExprBlock, ExprMacro, ExprMethodCall, FnArg, Ident, ItemEnum, ItemFn, ItemStruct, Path, PathSegment, 
    PredicateType, Stmt, Token, Type, TypePath, Visibility, WhereClause, WherePredicate, GenericParam, PathArguments,
    parenthesized
};
use std::{collections::HashSet,str::FromStr};

macro_rules! stringify_path {
    ( $path:path ) => {
        stringify!($path)
    }
}

/* #region define_service_type ***************************************************/

/// macro to define a composite type from MicroService implementing types. This is used for [`odin_server::Server`] initialization
/// and avoids the problem that we cannot use trait objects to put the service components into a generic container
/// (such as `Vec<Box<dyn MicroService>>`) since the MicroService trait is not object safe - it has methods returning
/// `impl Future<..>` types. Use like so:
/// ```
///   define_service_type! { TrackServices = ImageryService + TrackService }
/// ```
/// which gets expanded into:
/// ```
///   struct TrackServices (ImageryService, TrackService);
///   impl MicroService for TrackServices {
///     fn router (&self, hserver: ActorHandle<ServerMsg>)->Option<Router> { 
///       // merges routers
///     }
///     fn send_init_ws_msg (&self, hserver: ActorHandle<ServerMsg>)->impl Future<Output=Result<()>>+Send { 
///       // calls send_init_ws_msg(hserver).await? for each component
///     }
///     fn handle_incoming_ws_msg (&self, hserver: ActorHandle<ServerMsg>, msg: &str)->impl Future<Output=Result<()>>+Send {
///       // calls handle_incoming_ws_msg(hserver,msg).await? for each component
///     }
///   }
/// ```
#[proc_macro]
pub fn define_service_type (item: TokenStream) -> TokenStream {
    let ServiceComposition {visibility, name, component_types }= syn::parse(item).unwrap();
    let merged_routes: Vec<TokenStream2> = component_types.iter().enumerate().map( |(idx,ct)|{
        quote! { if let Some(r) = self.#idx.router(hserver) { router = router.merge(r); } }
    }).collect();
    let init_msg: Vec<TokenStream2> = component_types.iter().enumerate().map( |(idx,ct)|{
        quote! { self.#idx.send_init_ws_msg(hserver).await? }
    }).collect();
    let incoming_msg: Vec<TokenStream2> = component_types.iter().enumerate().map( |(idx,ct)|{
        quote! { self.#idx.handle_incoming_ws_msg(hserver, msg).await? }
    }).collect();

    let new_item: TokenStream = quote! {
        #visibility struct #name (
            #( #component_types ),*
        );
        impl MicroService for #name {
            fn router (&self, hserver: ActorHandle<ServerMsg>)->Option<Router> {
                let mut router = Router::new();
                #(#merged_routes)*
                Some(router)
            }
            fn send_init_ws_msg (&self, hserver: ActorHandle<ServerMsg>)->impl Future<Output=Result<()>> + Send {
                #(#init_msg);*
            }
            fn handle_incoming_ws_msg (&self, hserver: ActorHandle<ServerMsg>, msg: &str)->impl Future<Output=Result<()>> + Send {
                #(#incoming_msg);*
            }
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());
    new_item
}

#[derive(Debug)]
struct ServiceComposition {
    visibility: Visibility,
    name: Ident,
    component_types: Vec<Path>
}

impl Parse for ServiceComposition {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let visibility: Visibility = parse_visibility(input);
        let name: Ident = input.parse()?;

        let lookahead = input.lookahead1();
        let component_types: Vec<Path> = if lookahead.peek(Token![=]) {
            let _: Token![=] = input.parse()?;
            let component_types = Punctuated::<Path,Token![+]>::parse_terminated(input)?;
            component_types.into_iter().collect()
        } else {
            Vec::new()
        };
        
        Ok( ServiceComposition { visibility, name, component_types })
    }
}

/* #endregion define_service_type */

/* #region define_struct **************************************************************/

///
/// define_struct! {
///     pub MyStruct<A>: Debug + Clone where A: Foo + Debug + Cone =
///         field_1: A,
///         field_2: Vec<String>   = Vec::new(),  // func call init
///         field_3: &'static str  = "blah",     // literal init
///         field_4: usize         = { field_3.len() }  // block init (with back-ref)
/// }
/// 
/// expanded into:
/// 
/// #[derive(Debug)]
/// pub struct MyStruct<A> where A: Foo + Debug {
///     field_1: A,
///     ...
/// }
/// impl <A> MyStruct<A> where A: Foo + Debug {
///     pub fn new (field_1: A)->Self {
///         let field_2: Vec<String> = Vec::new();
///         let field_3: &'static str = "blah";
///         let field_4: usize = { field_3.len() };
///         MyStruct { field_1, field_2, field_3, field_4 }
///     } 
/// }
/// 
#[proc_macro]
pub fn define_struct (item: TokenStream) -> TokenStream {
    let StructSpec{ attrs, visibility, name, generic_params, derives, where_clause, field_specs } = match syn::parse(item) {
        Ok(struct_spec) => struct_spec,
        Err(e) => panic!( "expected \"structName [: Trait,..] = fieldSpec, ..\" got error: {:?}", e)
    };
    let generics = if generic_params.is_empty() { quote!{} } else { quote! { < #( #generic_params ),* > } };
    let derive_clause = if derives.is_empty() { quote!{} } else { quote! { #[derive( #( #derives ),* )] } };
    let inherent_impl = get_inherent_impl( &visibility, &name, &generic_params, &where_clause,  &field_specs);

    let new_item: TokenStream = quote! {
        #derive_clause
        #( #attrs )*
        #visibility struct #name #generics #where_clause {
            #( #field_specs ),*
        }
        #inherent_impl
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());
    new_item
}

fn get_inherent_impl (visibility: &Visibility, name: &Ident, generic_params: &Vec<GenericParam>, where_clause: &Option<WhereClause>, field_specs: &Vec<FieldSpec>)->TokenStream2 {
    let ctor_arg_list: TokenStream2 = get_ctor_arg_list( field_specs);
    let mut generic_names = get_generic_names(generic_params);
    let generics = if generic_params.is_empty() { quote!{} } else { quote! { < #( #generic_params ),* > } };
    let field_names: Vec<&Ident> = field_specs.iter().map( |f| &f.name).collect();
    let init_stmts: TokenStream2 = get_ctor_init_stmts( field_specs);

    quote!{
        impl #generic_names #name #generics #where_clause {
            #visibility fn new ( #ctor_arg_list )->Self {
                #init_stmts
                #name { #( #field_names ),* }
            }
        }
    }
}

fn get_generic_names (generic_params: &Vec<GenericParam>)->TokenStream2 {
    let mut ts = TokenStream2::new();

    if !generic_params.is_empty() {
        let mut is_first = true;
        ts.append( Punct::new('<', Spacing::Alone));
        for g in generic_params.iter() {
            if !is_first {
                ts.append( Punct::new(',', Spacing::Alone));
            } else { 
                is_first = false;
            }

            match g {
                GenericParam::Type(g) => ts.append( g.ident.clone()),
                GenericParam::Lifetime(g) => {
                    ts.append( Punct::new('\'', Spacing::Joint));
                    ts.append( g.lifetime.ident.clone());
                }
                GenericParam::Const(g) => ts.append( g.ident.clone())
            }
        }
        ts.append( Punct::new('>', Spacing::Alone));
    }

    ts
}

fn get_ctor_arg_list (field_specs: &Vec<FieldSpec>)->TokenStream2 {
    let no_init_args: Vec<TokenStream2> = field_specs.iter().filter(|f| f.init_expr.is_none()).map(|f|{
        let ident = &f.name;
        let field_type = &f.field_type;
        quote! { #ident : #field_type }
    }).collect();

    if !field_specs.is_empty() {
        quote!{ #( #no_init_args ),* }
    } else {
        quote!{}
    }
}

fn get_ctor_init_stmts (field_specs: &Vec<FieldSpec>)->TokenStream2 {
    let mut ts = TokenStream2::new();
    for f in field_specs {
        if let Some(init) = &f.init_expr {
            let name = &f.name;
            let ftype = &f.field_type;
            ts.append_all( quote!{ let #name : #ftype = #init; } );
        }
    }
    ts
}

struct StructSpec {
    attrs: Vec<Attribute>,
    visibility: Visibility,
    name: Ident,
    generic_params:Vec<GenericParam>,
    derives: Vec<Path>,
    where_clause: Option<WhereClause>,
    field_specs: Vec<FieldSpec>,
}

impl Parse for StructSpec {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let attrs: Vec<Attribute> = input.call(Attribute::parse_outer)?;
        let visibility: Visibility = parse_visibility(input);
        let name: Ident = input.parse()?;

        let mut generic_params: Vec<GenericParam> = Vec::new();
        let mut lookahead = input.lookahead1();
        if !input.is_empty() && lookahead.peek(Token![<]) {
            input.parse::<Token![<]>()?;
            generic_params = Punctuated::<GenericParam,Token![,]>::parse_separated_nonempty(input)?.into_iter().collect();
            input.parse::<Token![>]>()?;
            lookahead = input.lookahead1();
        }

        let mut derives: Vec<Path> = Vec::new();
        if !input.is_empty() && lookahead.peek(Token![:]) {
            input.parse::<Token![:]>()?;
            derives = Punctuated::<Path,Token![+]>::parse_separated_nonempty(input)?.into_iter().collect();
            lookahead = input.lookahead1();
        }

        let mut where_clause: Option<WhereClause> = None;
        if !input.is_empty() && lookahead.peek(Token![where]) {
            where_clause = Some(input.parse::<WhereClause>()?);
            lookahead = input.lookahead1();
        }

        let mut field_specs: Vec<FieldSpec> = Vec::new();
        if !input.is_empty() && lookahead.peek(Token![=]) {
            input.parse::<Token![=]>()?;
            field_specs = Punctuated::<FieldSpec,Token![,]>::parse_separated_nonempty(input)?.into_iter().collect();
        }

        Ok(StructSpec { attrs, visibility, name, generic_params, derives, where_clause, field_specs })
    }
}


struct FieldSpec {
    attrs: Vec<Attribute>,
    visibility: Visibility,
    name: Ident,
    colon_token: Colon,
    field_type: Type,
    init_expr: Option<Expr>,
}

impl Parse for FieldSpec {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let attrs: Vec<Attribute> = input.call(Attribute::parse_outer)?;
        let visibility: Visibility = parse_visibility(input);
        let name: Ident = input.parse()?;
        let colon_token: Colon = input.parse::<Token![:]>()?;
        let field_type: Type = input.parse::<Type>()?;

        let mut lookahead = input.lookahead1();
        let mut init_spec: Option<Expr> = None;
        if !input.is_empty() && lookahead.peek(Token![=]) {
            input.parse::<Token![=]>()?;
            init_spec = Some(input.parse::<Expr>()?);
        }

        Ok( FieldSpec{ attrs, visibility, name, colon_token, field_type, init_expr: init_spec })
    }
}

impl ToTokens for FieldSpec {
    fn to_tokens(&self, tokens: &mut TokenStream2) {
        for a in &self.attrs { a.to_tokens(tokens); }
        self.visibility.to_tokens(tokens);
        self.name.to_tokens(tokens);
        self.colon_token.to_tokens(tokens);
        self.field_type.to_tokens(tokens);
    }
}

/* #endregion define_struct */

/* #region define_algebraic_type ******************************************************/

/// macro to define algebraic types (using a Haskell'ish syntax), which are mapped into enums
/// whose variant names are transparent (automatically generated from element types).
/// 
/// variant names are computed from their respective types and are implemented as simple 1-element
/// tuple structs. The encoding uses unicode characters that resemble type tokens ('<' etc) but
/// are not likely to be used in normal code. We choose readability over typing since the user
/// is not supposed to enter those variant names manually and use the [`match_algebraic_type`] macro
/// instead (which uses the same encoding of types).
/// 
/// Note: if message variants use path types (e.g. `std::vec::Vec`) the same notation
/// has to be used in both [`define_algebraic_type`] and [`match_algebraic_type`] 
/// 
/// The macro supports an optional derive clause
/// ```
///     define_algebraic_type! { MyEnum: Trait1,... = ... }
/// ```
/// that is expanded into a respective `#[derive(Trait1,..)` macro for the resulting enum.
/// 
/// As a convenience feature it also supports optional method definitions that are expanded for
/// all variants if their bodies include `__` (double underscore) as variable names. If present these
/// methods are turned into an inherent impl for the enum. 
/// 
/// Example:
/// ```
/// struct A { id: u64 }
/// struct B<T> { id: u64, v: T }
/// 
/// define_algebraic_type! {
///     pub MyMsg: Clone = A | B<std::vec::Vec<(u32,&'static str)>>
///     pub fn id(&self)->u64 { __.id }
///     pub fn description()->'static str { "my message enum" }
/// }
/// ```
/// This is expanded into
/// ```
/// #[derive(Debug)]
/// #[derive(Clone)]
/// pub enum MyMsg {
///     A (A),
///     BᐸstdːːvecːːVecᐸ𛰙u32ˎᴿʽstaticˑstr𛰚ᐳᐳ (B<std::vec::Vec<(u32,&'static str)>>),
/// }
/// impl MyMsg {
///     pub fn id(&self)->u64 {
///         match self {
///             Self::A (__) => { __.id }
///             Self::BᐸstdːːvecːːVecᐸ𛰙u32ˎᴿʽstaticˑstr𛰚ᐳᐳ (__) => { _.id }
///         }
///     }
/// }
/// impl From<A> for MyMsg {...}
/// impl From<B<std::vec::Vec<(u32,&'static str)>>> for MyMsg {...}
/// ```
#[proc_macro]
pub fn define_algebraic_type (item: TokenStream) -> TokenStream {
    let AdtEnum {visibility, name, derives, variant_types, methods }= match syn::parse(item) {
        Ok(adt) => adt,
        Err(e) => panic!( "expected \"adtName [: Trait,..] = variantType | ..  [ func ... ]\" got error: {:?}", e)
    };

    let mut variant_names = get_variant_names_from_types(&variant_types);
    let derive_clause = if derives.is_empty() { quote!{} } else { quote! { #[derive( #( #derives ),* )] } };
    let inherent_impl = if methods.is_empty() { quote!{} } else { build_inherent_impl( &name, &variant_names, &methods) };

    let new_item: TokenStream = quote! {
        #derive_clause
        #visibility enum #name {
            #( #variant_names ( #variant_types ) ),*
        }
        #inherent_impl
        #(
            impl From<#variant_types> for #name {
                fn from (v: #variant_types)->Self { #name::#variant_names(v) }
            }
        )*
        impl std::fmt::Debug for #name {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                match self {
                    #( Self::#variant_names (msg) => write!(f, concat!( stringify!(#name), "::", stringify!(#variant_names))) ),*
                }
            }
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());
    new_item
}

fn build_inherent_impl (enum_name: &Ident, variant_names: &Vec<Ident>, methods: &Vec<ItemFn>)->TokenStream2 {
    let mthds: Vec<TokenStream2> = methods.iter().map( |m| build_enum_method( variant_names, m)).collect();

    quote! {
        impl #enum_name {
            #( #mthds )*
        }
    }
}

fn build_enum_method (variant_names: &Vec<Ident>, method: &ItemFn)->TokenStream2 {
    let vis = &method.vis;
    let sig = &method.sig;
    let blk = &method.block;

    let mut block_analyzer = BlockAnalyzer::new();
    block_analyzer.visit_block(blk);

    if block_analyzer.uses_variant { // expand for all variants
        // variant name placeholders:
        // unfortunately we cannot use a single '_' wildcard since it is not a normal ident and cannot be replaced easily
        // we also might use normal wildcards in the function body.
        // the next best choice is '__', which is actually a valid ident and rarely used 

        quote! {
            #vis #sig {
                match self {
                    #( Self::#variant_names ( __ ) => #blk )*
                }
            }
        }
    } else { // expand verbatim
        quote! {
            #vis #sig #blk
        }
    }
}

struct BlockAnalyzer { uses_variant: bool }
impl BlockAnalyzer {
    fn new()->Self { BlockAnalyzer { uses_variant: false } }
}

impl<'a> Visit<'a> for BlockAnalyzer {
    fn visit_ident(&mut self, ident: &'a Ident) {
        if ident.to_string() == "__" { 
            self.uses_variant = true;
        }
        visit::visit_ident(self, ident)
    }
}

/* #endregion define_algebraic_type */

/* #region define_actor_msg_type ***********************************************************/

/// the odin_actor specific version of the general [`define_algebraic_type`] macro.
/// this automatically adds system messages (_Start_,_Terminate_,..) variants and
/// a [`odin_actor::DefaultReceiveAction`]` impl.
/// 
/// Example:
/// ```
/// define_actor_msg_type! { pub MyActorMsg = A | B }
/// ```
/// This is expanded into
/// ```
/// #[derive(Debug)]
/// pub enum MyActorMsg {
///     A (A),
///     B (B),
///     _Start_ (_Start_), ... _Terminate_ (_Terminate_)
/// }
/// impl FromSysMsg for MyActorMsg {...}
/// impl From<A> for MyActorMsg {...}
/// impl From<B> for MyMsg {...}
/// impl DefaultReceiveAction for MyActorMsg {...}
/// 
#[proc_macro]
pub fn define_actor_msg_type (item: TokenStream) -> TokenStream {
    let AdtEnum {visibility, name, derives, mut variant_types, methods }= syn::parse(item).unwrap();
    for var_type in get_sys_msg_types() {
        variant_types.push(var_type)
    }

    let mut variant_names = get_variant_names_from_types(&variant_types);
    for var_name in get_sys_msg_idents() {
        variant_names.push(var_name)
    }

    let derive_clause = if derives.is_empty() { quote!{} } else { quote! { #[derive( #( #derives ),* )] } };
    let inherent_impl = if methods.is_empty() { quote!{} } else { build_inherent_impl( &name, &variant_names, &methods) };

    let new_item: TokenStream = quote! {
        #derive_clause
        #visibility enum #name {
            #( #variant_names ( #variant_types ) ),*
        }
        #inherent_impl
        impl FromSysMsg for #name {}
        #(
            impl From<#variant_types> for #name {
                fn from (v: #variant_types)->Self { #name::#variant_names(v) }
            }
        )*
        impl std::fmt::Debug for #name {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                match self {
                    #( #name::#variant_names (msg) => write!(f, "{:?}", msg), )*
                }
            }
        }
        impl DefaultReceiveAction for #name {
            fn default_receive_action (&self)->ReceiveAction {
                match self {
                    #name::_Terminate_(msg) => ReceiveAction::Stop,
                    #name::_Ping_(msg) => { msg.store_response(); ReceiveAction::Continue }
                    #name::_Exec_(msg) => { msg.0(); ReceiveAction::Continue }
                    _ => ReceiveAction::Continue
                }
            }
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

fn get_variant_names_from_types (variant_types: &Vec<Path>)->Vec<Ident> {
    variant_types.iter().map( |p| {
        let ps = path_to_string( p);
        let ps_mangled = mangle(ps.as_str());
        Ident::new( &ps_mangled.as_str(), Span::call_site())
    }).collect()
}

#[derive(Debug)]
struct AdtEnum {
    visibility: Visibility,
    name: Ident,
    derives: Vec<Path>,
    variant_types: Vec<Path>,
    methods: Vec<ItemFn>
}

impl Parse for AdtEnum {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let visibility: Visibility = parse_visibility(input);
        let name: Ident = input.parse()?;
        let mut derives: Vec<Path> = Vec::new();

        let mut lookahead = input.lookahead1();
        if !input.is_empty() && lookahead.peek(Token![:]) {
            input.parse::<Token![:]>()?;
            derives = Punctuated::<Path,Token![+]>::parse_separated_nonempty(input)?.into_iter().collect();
            lookahead = input.lookahead1();
        }

        let variant_types: Vec<Path> = if !input.is_empty() && lookahead.peek(Token![=]) {
            input.parse::<Token![=]>()?;
            let variant_types = Punctuated::<Path,Token![|]>::parse_separated_nonempty(input)?;
            variant_types.into_iter().collect()
        } else {
            Vec::new()
        };
        
        let mut methods: Vec<ItemFn> = Vec::new();
        lookahead = input.lookahead1();
        while !input.is_empty() && (lookahead.peek(Token![fn]) || lookahead.peek(Token![pub])) {
            let mth: ItemFn = input.parse()?;
            methods.push(mth);
            lookahead = input.lookahead1()
        }

        Ok( AdtEnum { visibility, name, derives, variant_types, methods })
    }
}

/* #endregion define_actor_msg_type */

/* #region match macros **********************************************************/

/// macro to match algebraic type values (enum variants) that were created with the
/// [`define_algebraic_type`] macro
/// Example:
/// ```
/// define_algebraic_type!( MyType = A | B<C,D> | E)
/// ...
/// match_algebraic_type! { my_type: MyType as
///   A => cont { println!("got an A : {}",my_type); }
///   x::B<C,D> => { println!("got a B<C,D>"); }
///   E => { println!("got an E") }
/// }
/// ```
#[proc_macro]
pub fn match_algebraic_type (item: TokenStream) -> TokenStream {
    let MsgMatch { msg_name, msg_type, match_arms }: MsgMatch = match syn::parse(item) {
        Ok(msg_match) => msg_match,
        Err(e) => panic!( "expected \"match_algebraic_type!( 𝑣𝑎𝑟𝑖𝑎𝑏𝑙𝑒𝑛𝑎𝑚𝑒𝑁𝑎𝑚𝑒:𝐸𝑛𝑢𝑚𝑇𝑦𝑝𝑒 as 𝑉𝑎𝑟𝑖𝑎𝑛𝑡𝑇𝑦𝑝𝑒 => {{..}}, ..)\", got {:?}", e)
    };

    let match_patterns: Vec<TokenStream2> = get_match_patterns(&msg_name, &msg_type, &match_arms);
    let match_actions: Vec<&MsgMatchAction> = match_arms.iter().map( |a| { &a.match_action }).collect();

    let new_item: TokenStream = quote! {
        match #msg_name {
            #(
                #match_patterns => #match_actions
            ),*
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

fn get_match_patterns(msg_name: &Ident, msg_type: &Path, match_arms: &Vec<MsgMatchArm>)->Vec<TokenStream2> {
    match_arms.iter().map(|a| {
        match &a.variant_spec {
            VariantSpec::Type(path) => {
                let variant_name = get_variant_name_from_match_arm(a);
                let maybe_mut = a.maybe_mut;
                quote!( #msg_type::#variant_name (#maybe_mut #msg_name))
            }
            VariantSpec::Wildcard => { quote!(_) }
        }
    }).collect()
}

/// the odin_actor specific version of the general [`match_algebraic_type`] macro.
/// this automatically adds system message (_Start_,_Terminate_,..) variants and
/// a default match arm that calls `msg.default_receive_action()`.
/// 
/// Match arm actions can use the [`cont`], [`stop`] and [`term`] macros to return
/// respective [`odin_actor::ReceiveAction`] values
/// 
/// Note: if message variants use path types (e.g. `std::vec::Vec`) the same notation
/// has to be used in both [`define_actor_msg_type`] and [`match_actor_msg_type`] 
/// 
/// Example:
/// ```
///     define_actor_msg_type! { MyActorMsg = x::A | B }
///     ...
///     match_actor_msg_type! { msg: MyActorMsg as 
///         x::A => cont! { println!("actor received an A = {:?}", msg) }
///         _Terminate_ => stop! { println!("actor terminated") }
///     }
/// ```
/// This is expanded into:
/// ```
///     match msg {
///        xːːA(msg) => { {println!("actor received an A = {:?}", msg)}; ReceiveAction::Continue }
///        _Terminate_(msg) => { {println!("actor terminated {:?}", msg)}; ReceiveAction::Stop }
///        _ => msg.default_receive_action()
///     }
/// ```
/// 
#[proc_macro]
pub fn match_actor_msg (item: TokenStream)->TokenStream {
    let MsgMatch { msg_name, msg_type, match_arms }: MsgMatch = syn::parse(item).unwrap();
    let variant_names: Vec<Ident> = get_variant_names_from_match_arms(&match_arms);
    let is_mut: Vec<&Option<Token![mut]>> = match_arms.iter().map( |a| { &a.maybe_mut }).collect();
    let match_actions: Vec<&MsgMatchAction> = match_arms.iter().map( |a| { &a.match_action }).collect();

    let new_item: TokenStream = quote! {
        match #msg_name {
            #( #msg_type::#variant_names (#is_mut #msg_name) => #match_actions, )*

            // this relies on Rust allowing duplicated match patterns and ignoring all but the first
            #msg_type::_Start_(_) => msg.default_receive_action(),
            #msg_type::_Ping_(_) => msg.default_receive_action(),
            #msg_type::_Timer_(_) => msg.default_receive_action(),
            #msg_type::_Exec_(_) => msg.default_receive_action(),
            #msg_type::_Pause_(_) => msg.default_receive_action(),
            #msg_type::_Resume_(_) => msg.default_receive_action(),
            #msg_type::_Terminate_(_) => msg.default_receive_action(),
            //_ => #msg_name . default_receive_action() // this would be a catch-all which would bypass the check for unmatched user messages
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

fn get_variant_name_from_match_arm (a: &MsgMatchArm)->Ident {
    let ps = variant_spec_to_string( &a.variant_spec);
    let ps_mangled = mangle(ps.as_str());
    Ident::new( &ps_mangled.as_str(), Span::call_site())
}

fn get_variant_names_from_match_arms (match_arms: &Vec<MsgMatchArm>)->Vec<Ident> {
    match_arms.iter().map( |a| get_variant_name_from_match_arm(a)).collect()
}

fn get_variant_types_from_match_arms (match_arms: &Vec<MsgMatchArm>)->Vec<Path> {
    let mut var_types: Vec<Path> = Vec::new();

    for a in match_arms {
        match &a.variant_spec {
            VariantSpec::Type(path) => {
                if let Some(last_seg) = path.segments.last() {
                    let name = last_seg.ident.to_string();
                    if !(name.starts_with("_") && name.ends_with("_")) {
                        var_types.push(path.clone())
                    }
                }
            }
            _ => {} // we are not interested in wildcards
        }
    }

    /* here we could add system messages but those should only be sent through the SysMsgReceiver trait, i.e. from the ActorSystem
    var_types.push( sys_msg_path("_Start_"));
    var_types.push( sys_msg_path("_Ping_"));
    var_types.push( sys_msg_path("_Timer_"));
    var_types.push( sys_msg_path("_Pause_"));
    var_types.push( sys_msg_path("_Resume_"));
    var_types.push( sys_msg_path("_Terminate_"));
    */

    var_types

}

fn sys_msg_path (name: &'static str)->Path {
    let crate_ident = Ident::new("odin_actor", Span::call_site());
    let ident = Ident::new( name, Span::call_site());
    let mut segments: Punctuated<PathSegment,PathSep> = Punctuated::new();
    segments.push( PathSegment { ident: crate_ident, arguments: PathArguments::None });
    segments.push( PathSegment{ ident, arguments: PathArguments::None });

    Path{ leading_colon: None, segments }
}

struct MsgMatch {
    msg_name: Ident, // the msg variable name to bind
    msg_type: Path, // the msg type to match
    match_arms: Vec<MsgMatchArm>
}

struct MsgMatchArm {
    variant_spec: VariantSpec,
    maybe_mut: Option<Token![mut]>,
    match_action: MsgMatchAction,
}

enum VariantSpec {
    Type(Path),
    Wildcard
}

enum MsgMatchAction {
    MethodCall(ExprMethodCall),
    Macro(ExprMacro),
    Block(Block),
}

impl ToTokens for MsgMatchAction {
    fn to_tokens(&self, tokens: &mut TokenStream2) {
        match self {
            MsgMatchAction::MethodCall(a) => a.to_tokens(tokens),
            MsgMatchAction::Macro(a) => a.to_tokens(tokens),
            MsgMatchAction::Block(a) => a.to_tokens(tokens),
        }
    }
}

impl Parse for MsgMatch {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let msg_name: Ident = input.parse()?;
        let _: Token![:] = input.parse()?;
        let msg_type: Path = input.parse()?;
        let _: Token![as] = input.parse()?;
        let match_arms = parse_match_arms(input)?;

        Ok( MsgMatch { msg_name, msg_type, match_arms } )
    }
}

// TODO - this should be consistent over all our ADTs
fn parse_match_arms (input: ParseStream)->Result<Vec::<MsgMatchArm>> {
    let mut match_arms = Vec::<MsgMatchArm>::new();
    
    while !input.is_empty() {
        let lookahead = input.lookahead1();
        let (variant_spec,is_mut) = if lookahead.peek( Token![_]) {
            let _: Token![_] = input.parse()?;
            (VariantSpec::Wildcard,Option::<Mut>::None)
        } else {
            let is_mut: Option<Token![mut]> = if lookahead.peek( Token![mut]) {
                Some(input.parse()?)
            } else { None };
    
            let path: Path = input.parse()?;
            (VariantSpec::Type(path),is_mut)
        };
        
        //--- the match 
        let _: Token![=>] = input.parse()?;
        let lookahead = input.lookahead1();
        let match_action = if lookahead.peek(Ident) {
            let mac: ExprMacro = input.parse()?;
            MsgMatchAction::Macro(mac)
        } else if lookahead.peek(token::Brace) {
            let block: Block = input.parse()?;
            MsgMatchAction::Block(block)
        } else if lookahead.peek( Token![self]) {
            let mthd_call: ExprMethodCall = input.parse()?;
            MsgMatchAction::MethodCall(mthd_call)
        } else {
            return Err(lookahead.error())
        };

        let lookahead = input.lookahead1();
        if lookahead.peek(Token![,]) { // FIXME - does not work! 
            let _: Token![,] = input.parse()?;
        }

        match_arms.push( MsgMatchArm { variant_spec, maybe_mut: is_mut, match_action } );
    }

    Ok(match_arms)
}


/* #endregion match macros */

/* #region actor receive definition ****************************************************************/

/// a wrapper around the body of an [`match_actor_msg_type`] that puts it into an ActorReceiver context
/// the match arms are the same but the header also needs to specify the actor state type
/// 
/// Example:
/// ```
/// impl_actor! { match msg: ResponderMsg for Responder as
///     Ask<Question,Answer> => ...
/// }
/// ```
#[proc_macro]
pub fn impl_actor (item: TokenStream) -> TokenStream {
    let ActorReceive { msg_name, msg_type, state_type, where_clause, match_arms }: ActorReceive = match syn::parse(item) {
        Ok(actor_receive) => actor_receive,
        Err(e) => panic!( "expected \"match 𝑚𝑠𝑔𝑉𝑎𝑟𝑁𝑎𝑚𝑒 for Actor<𝑆𝑡𝑎𝑡𝑒𝑇𝑦𝑝𝑒,𝑀𝑠𝑔𝑇𝑦𝑝𝑒> 𝑾ℎ𝑒𝑟𝑒𝐶𝑙𝑎𝑢𝑠𝑒﹖ as 𝑀𝑠𝑔𝑇𝑦𝑝𝑒𝑉𝑎𝑟𝑖𝑎𝑛𝑡 => {{..}}, ..\", got {:?}", e)
    };

    let variant_names: Vec<Ident> = get_variant_names_from_match_arms(&match_arms);
    //let variant_types: Vec<Path> = get_variant_types_from_match_arms(&match_arms); // if we need to do explicit trait impls for variant types
    let is_mut: Vec<&Option<Token![mut]>> = match_arms.iter().map( |a| { &a.maybe_mut }).collect();
    let match_actions: Vec<&MsgMatchAction> = match_arms.iter().map( |a| { &a.match_action }).collect();

    let typevars: Vec<&Path> = if let Some(ref wc) = where_clause { collect_typevars( wc) } else { Vec::new() }; 
    let typevar_tokens: TokenStream2 = if typevars.is_empty() { quote! {} } else {
        quote! { < #( #typevars ),* > }
    };

    let new_item: TokenStream = quote! {
        impl #typevar_tokens ActorReceiver<#msg_type> for Actor<#state_type,#msg_type> #where_clause {
            async fn receive (&mut self, msg: #msg_type)->ReceiveAction {
                #[allow(unused_variables)] // some match arms might not use msg_name
                match #msg_name {
                    #( #msg_type::#variant_names (#is_mut #msg_name) => #match_actions, )*

                    // this relies on Rust allowing duplicated match patterns and ignoring all but the first matching arm
                    #msg_type::_Start_(_) => #msg_name.default_receive_action(),
                    #msg_type::_Ping_(_) => #msg_name.default_receive_action(),
                    #msg_type::_Timer_(_) => #msg_name.default_receive_action(),
                    #msg_type::_Exec_(_) => #msg_name.default_receive_action(),
                    #msg_type::_Pause_(_) => #msg_name.default_receive_action(),
                    #msg_type::_Resume_(_) => #msg_name.default_receive_action(),
                    #msg_type::_Terminate_(_) => #msg_name.default_receive_action(),
                    //_ => #msg_name . default_receive_action() // this would be a catch-all which would cut off the check for unmatched user messages
                }
            }
            fn hsys(&self)->&ActorSystemHandle { &self.hsys }
        }
        /* explicit trait impl for variant types would go here 
        #( 
            impl MsgReceiver< #variant_types > for ActorHandle< #msg_type > {
                fn send_msg<'a> (&'a self, m: #variant_types)->SendMsgFuture<'a> { self.send_actor_msg(m.into()) }
                fn move_send_msg (self, m: MsgType)->MoveSendMsgFuture { self.move_send_msg(m.into()) }
                fn timeout_send_msg<'a> (&self, m: MsgType, to: Duration)->TimeoutSendMsgFuture<'a> { self.timeout_send_actor_msg( m.into(), to) }
                fn timeout_move_send_msg (self, m: MsgType, to: Duration)->TimeoutMoveSendMsgFuture { self.timeout_move_send_msg( m, to) }
                fn try_send_msg (&self, m:MsgType)->Result<()> { self.try_send_actor_msg(m) }
            }
        )*
        */
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

struct ActorReceive {
    msg_name: Ident,
    msg_type: Path,
    state_type: Path,
    where_clause: Option<WhereClause>, 
    match_arms: Vec<MsgMatchArm>
} 

impl Parse for ActorReceive {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let _: Token![match] = input.parse()?;
        let msg_name: Ident = input.parse()?;
        let _: Token![for] = input.parse()?;
        parse_ident_value(input, "Actor")?;
        let _: Token![<] = input.parse()?;
        let state_type: Path = input.parse()?;
        let _: Token![,] = input.parse()?;
        let msg_type: Path = input.parse()?;
        let _: Token![>] = input.parse()?;

        let where_clause: Option<WhereClause> = input.parse()?;

        let _: Token![as] = input.parse()?;

        let match_arms = parse_match_arms(input)?;

        Ok( ActorReceive { msg_name, msg_type, state_type, where_clause, match_arms } )
    }
}

fn collect_typevars<'a> (where_clause: &'a WhereClause) -> Vec<&'a Path> {
    let mut typevars = Vec::new();

    for where_predicate in &where_clause.predicates {
        if let WherePredicate::Type(predicate_type) = where_predicate {
            if let Type::Path(ref type_path)  = predicate_type.bounded_ty {
                typevars.push( &type_path.path)
            }
        }
    }

    typevars
}

/* #endregion actor receive definition */

/* #region match arm macros  *****************************************************/

/// statement (block) wrapper macro to be used in match arm expressions that makes sure we return 
/// [`ReceiveAction::Continue`] from this match arm 
/// 
/// Example:
/// ```
///     match_actor_msg_type! { msg: MyActorMsg as 
///         A => cont! { println!("actor received an A = {:?}", msg) }
///         ...
/// ```
/// This is expanded into:
/// ```
///     match msg {
///         A(msg) => { {println!("actor received an A = {:?}", msg)}; ReceiveAction::Continue }
///         ...
/// ```
#[proc_macro]
pub fn cont (ts: TokenStream)->TokenStream {
    expand_msg_match_action( ts, quote! { ReceiveAction::Continue })
}

/// statement (block) wrapper macro to be used in match arm expressions that makes sure we return 
/// [`odin_actor::ReceiveAction::Stop`] from this match arm. See [`cont`] for details.
#[proc_macro]
pub fn stop (ts: TokenStream)->TokenStream {
    expand_msg_match_action( ts, quote! { ReceiveAction::Stop })
}

/// statement (block) wrapper macro to be used in match arm expressions that makes sure we return 
/// [`odin_actor::ReceiveAction::Stop`] from this match arm. See [`cont`] for details.
#[proc_macro]
pub fn term (ts: TokenStream)->TokenStream {
    expand_msg_match_action( ts, quote! { ReceiveAction::RequestTermination })
}

/* #endregion match arm macros  */

/* #region spawn_actor ***********************************************************/

#[proc_macro]
pub fn spawn_actor (item: TokenStream)->TokenStream {
    let SpawnActor { asys_name, aname_expr, astate_expr, channel_bounds } = match syn::parse(item) {
        Ok(actor_receive) => actor_receive,
        Err(e) => panic!( "expected \"spawn_actor!( 𝑎𝑐𝑡𝑜𝑟𝑆𝑦𝑠𝑡𝑒𝑚, 𝑎𝑐𝑡𝑜𝑟𝑁𝑎𝑚𝑒, 𝑎𝑐𝑡𝑜𝑟𝑆𝑡𝑎𝑡𝑒 [,𝑐ℎ𝑎𝑛𝑒𝑙𝐵𝑜𝑢𝑛𝑑𝑠])\", got {:?}", e)
    };
    let cbounds = if let Some(channel_bounds) = channel_bounds { quote!{#channel_bounds} } else { quote!{ DEFAULT_CHANNEL_BOUNDS} };
    
    let new_item: TokenStream = quote! { 
        #asys_name.spawn_actor( #asys_name.new_actor( #aname_expr, #astate_expr, #cbounds)) 
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

struct SpawnActor {
    asys_name: Ident,
    aname_expr: Expr,
    astate_expr: Expr,
    channel_bounds: Option<Expr>
}

impl Parse for SpawnActor {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let asys_name: Ident = input.parse()?;
        let _: Token![,] = input.parse()?;
        let aname_expr: Expr = input.parse()?;
        let _: Token![,] = input.parse()?;
        let astate_expr: Expr = input.parse()?;

        let lookahead = input.lookahead1();
        let channel_bounds = if lookahead.peek( Token![,]) {
            let _: Token![,] = input.parse()?;
            let bounds_expr: Expr = input.parse()?;
            Some(bounds_expr)
        } else {
            None
        };

        Ok( SpawnActor { asys_name, aname_expr, astate_expr, channel_bounds } )
    }
}

#[proc_macro]
pub fn spawn_pre_actor (item: TokenStream)->TokenStream {
    let SpawnPreActor { asys_name, h_pre_expr, astate_expr } = match syn::parse(item) {
        Ok(actor_receive) => actor_receive,
        Err(e) => panic!( "expected \"spawn_pre_actor!( 𝑎𝑐𝑡𝑜𝑟𝑆𝑦𝑠𝑡𝑒𝑚, 𝑝𝑟𝑒𝐴𝑐𝑡𝑜𝑟𝐻𝑎𝑛𝑑𝚤𝑒, 𝑎𝑐𝑡𝑜𝑟𝑆𝑡𝑎𝑡𝑒)\", got {:?}", e)
    };

    let new_item: TokenStream = quote! {
        #asys_name.spawn_actor( #asys_name.new_pre_actor( #h_pre_expr, #astate_expr))
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

struct SpawnPreActor {
    asys_name: Ident,
    h_pre_expr: Expr,
    astate_expr: Expr,
}

impl Parse for SpawnPreActor {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let asys_name: Ident = input.parse()?;
        let _: Token![,] = input.parse()?;
        let h_pre_expr: Expr = input.parse()?;
        let _: Token![,] = input.parse()?;
        let astate_expr: Expr = input.parse()?;

        Ok( SpawnPreActor { asys_name, h_pre_expr, astate_expr})
    }
}

/* #endregion spawn_actor */

/* #region actor lists ***********************************************************/

/// macro to define structs that implement [`ActorMsgList`] 
///  ```
///  define_actor_send_msg_list!{ MyMsgList (Msg1) : Actor1Msg, Actor2Msg }
///  let my_msg_list = MyMsgList( ah1, ah2);
///  ... async { .. my_msg_list.send_msg( Msg1(42)).await }
///  ```
///  which gets expanded into:    
///  ```
///  struct MyMsgList (ActorHandle<Actor1Msg>,ActorHandle<Actor2Msg>);
///  impl ActorSendMsgList<Msg1> for MyMsgList {
///      async fn send_msg(&self,m:Msg1)->Result<()> {
///          self.0.send_msg(m.clone()).await?;
///          self.1.send_msg(m).await
///      }
///  }
///  ```
#[proc_macro]
pub fn define_actor_send_msg_list (item: TokenStream)->TokenStream {
    let ActorMsgList{ name, data_type, msg_types} = match syn::parse(item) {
        Ok(msg_list) => msg_list,
        Err(e) => panic!( "failed to parse ActorSendMsgList definition {:?}", e)
    };

    let max_idx = msg_types.len()-1;
    let stmts: Vec<TokenStream2> = msg_types.iter().enumerate().map(|(idx,a)| {
        let idx_tok = Literal::usize_unsuffixed(idx); 
        if idx < max_idx { quote!{ self.#idx_tok.send_msg(msg.clone()).await? } } else { quote!{ self.#idx_tok.send_msg(msg).await } }
    }).collect();

    let new_item: TokenStream = quote! {
        struct #name ( #( ActorHandle< #msg_types > ),* );
        impl ActorSendMsgList< #data_type > for #name {
            async fn send_msg(&self, msg: #data_type)->odin_actor::Result<()> {
                #( #stmts );*
            }
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

struct ActorMsgList {
    name: Ident,
    data_type: Path,
    msg_types: Vec<Path>
}

impl Parse for ActorMsgList {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let name: Ident = input.parse()?;

        let arg_content;
        let _data_paren: Paren = parenthesized!( arg_content in input);
        let data_type: Path = arg_content.parse()?;

        let _ : Token![:] = input.parse()?;

        let mut msg_types: Vec<Path> = Vec::new();
        while !input.is_empty() {
            let msg_type: Path = input.parse()?;
            msg_types.push(msg_type);

            let lookahead = input.lookahead1();
            if lookahead.peek( Comma) { let _: Comma = input.parse()?; }
        }

        Ok( ActorMsgList{ name, data_type, msg_types } )
    }
}

/// macro to define structs that implement [`ActorActionList`]
/// ```
/// define_actor_action_list! { MyActionList (data:&u64) use actor_handle:
///   Actor1Msg => actor_handle.send_msg( Msg1(*data)).await,
///   Actor2Msg => actor_handle.try_send_msg( Msg2(*data))
/// }
/// let my_action_list = MyActionList( ah1, ah2);
/// ... async { let my_data: u64; ... my_action_list.execute( &my_data).await }
/// ```
/// which gets expanded into
/// ```
/// struct MyActionList (ActorHandle<Actor1Msg>,ActorHandle<Actor2Msg>);
/// impl ActorActionList<u64> for MyActionList {
/// async fn execute (&self, data: &u64)->Result<()> {
///     let mut actor_handle;
///     actor_handle = &self.0; actor_handle.send_msg( Msg1(*data).into()).await?;
///     actor_handle = &self.1; actor_handle.try_send_msg( Msg2(*data).into())
/// }
/// ```
/// Note that the provided execute() argument type has to be a simple reference
/// (no slices such as `&'static str` allowed) since we don't want to add explicit
/// lifetimes to the definition
#[proc_macro]
pub fn define_actor_action_list (item: TokenStream)->TokenStream {
    let ActorActionList{ struct_name, data_vars, self_name, actions} = match syn::parse(item) {
        Ok(action_list) => action_list,
        Err(e) => panic!( "failed to parse ActorActionList definition {:?}", e)
    };

    if data_vars.len() != 1 { panic!("expected single execute(arg) argument") }
    let v_base = get_type_base( &data_vars.first().unwrap().var_type);

    let msg_types: Vec<&Path> = actions.iter().map(|a| &a.msg_type).collect();
    let stmts = get_action_stmts( &self_name, &actions);

    let new_item: TokenStream = quote! {
        struct #struct_name ( #( ActorHandle< #msg_types > ),* );
        impl ActorActionList< #v_base > for #struct_name {
            async fn execute (&self, #data_vars)->odin_actor::Result<()> {
                #( #stmts );*
            }
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}


/// macro to define structs that implement [`ActorAction2List`]
/// this follows the same pattern as [`define_actor_action_list`] with the difference
/// that we provide two `execute(..)` arguments. One is usually from the receiver state, 
/// the other one provided by the trigger (message). It is up to the concrete receiver
/// to decide which one is which.
#[proc_macro]
pub fn define_actor_action2_list (item: TokenStream)->TokenStream {
    let ActorActionList{ struct_name, data_vars, self_name, actions} = match syn::parse(item) {
        Ok(action_list) => action_list,
        Err(e) => panic!( "failed to parse ActorActionList definition {:?}", e)
    };

    if data_vars.len() != 2 { panic!("expected two execute(arg1,arg2) arguments") }
    let v1_base = get_type_base(&data_vars.first().unwrap().var_type);
    let v2_base = get_type_base(&data_vars.last().unwrap().var_type);

    let msg_types: Vec<&Path> = actions.iter().map(|a| &a.msg_type).collect();
    let stmts = get_action_stmts( &self_name, &actions);

    let new_item: TokenStream = quote! {
        struct #struct_name ( #( ActorHandle< #msg_types > ),* );
        impl ActorAction2List< #v1_base, #v2_base > for #struct_name {
            async fn execute (&self, #data_vars)->odin_actor::Result<()> {
                #( #stmts );*
            }
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

fn get_action_stmts (self_name: &Ident, actions: &Vec<ActorAction>)->Vec<TokenStream2> {
    let max_idx = actions.len()-1;

    actions.iter().enumerate().map(|(idx,a)| {
        let result_expr = &a.result_expr;
        let idx_tok = Literal::usize_unsuffixed(idx); 
        if idx < max_idx { 
            quote!{ let #self_name = &self.#idx_tok; #result_expr ? } 
        } else { 
            quote!{ let #self_name = &self.#idx_tok; #result_expr } 
        }
    }).collect()
}

struct ActorActionList {
    struct_name: Ident,
    data_vars: Punctuated<TypedVar,Comma>,
    self_name: Ident,
    actions: Vec<ActorAction>
}

impl Parse for ActorActionList {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let _: For = input.parse()?;
        let self_name: Ident = input.parse()?;

        let _: In = input.parse()?;
        let struct_name: Ident = input.parse()?;

        let arg_input;
        let _data_paren: Paren = parenthesized!( arg_input in input);
        let data_vars: Punctuated<TypedVar,Comma> = Punctuated::parse_separated_nonempty(&arg_input)?;

        let _ : Token![:] = input.parse()?;

        let mut actions = Vec::<ActorAction>::new();
        while !input.is_empty() {
            let a: ActorAction = input.parse()?;
            actions.push(a);
    
            let lookahead = input.lookahead1();
            if lookahead.peek( Comma) { let _: Comma = input.parse()?; }
        }
    
        Ok( ActorActionList{struct_name, data_vars, self_name, actions} )
    }
}

struct ActorAction {
    msg_type: Path,
    result_expr: Expr
}
impl Parse for ActorAction {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let msg_type: Path = input.parse()?;
        let _: Token![=>] = input.parse()?;
        let result_expr: Expr = input.parse()?;
        // TODO - should check here if expr type is Result<(),OdinActorError>
        Ok(ActorAction{ msg_type, result_expr })
    }
}

/* #endergion actor lists */

/* #region fnmut *****************************************************************/

/// [([mut] id = expr {, ...}) =>] [| id [: type] {, ...} |] expr )

#[proc_macro]
pub fn fn_mut (item: TokenStream)->TokenStream {
    let FnMutSpec{ var_bindings, args, body} = match syn::parse(item) {
        Ok(spec) => spec,
        Err(e) => panic!( "failed to parse fn_mut definition {:?}", e)
    };

    let new_item: TokenStream =quote! {
        {
            #( let #var_bindings ;)*
            move | #( #args ),* | { #body }
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

struct FnMutSpec {
    var_bindings: Vec<VarAssign>,
    args: Vec<OptTypedVar>,
    body: Expr
}

impl Parse for FnMutSpec {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let mut var_bindings: Vec<VarAssign> = Vec::new();
        let mut lookahead = input.lookahead1();
        if lookahead.peek( Paren) {
            let var_input;
            let _var_paren: Paren = parenthesized!( var_input in input);
            let mut dvars: Punctuated<VarAssign,Comma> = Punctuated::parse_separated_nonempty(&var_input)?;
            var_bindings = dvars.into_iter().collect::<Vec<_>>().into();

            let _: Token![=>] = input.parse()?;
            lookahead = input.lookahead1();
        }

        let mut args: Vec<OptTypedVar> = Vec::new();
        if lookahead.peek( Token![|]) {
            let _: Token![|] = input.parse()?;
            loop {
                args.push( input.parse()?);
                lookahead = input.lookahead1();
                if !lookahead.peek(Token![,]) { break; } else { input.parse::<Token![,]>()?; }
            }
            input.parse::<Token![|]>()?;
        }

        let body: Expr = input.parse()?;

        Ok( FnMutSpec{var_bindings, args, body} )
    }
}

/* #endregion fnmut */

/* #region support funcs *********************************************************/

// just a simple "var_name: var_type" fragment that can be used in various places where the full
// complexity of respective syn types (pattern matching, visibility etc) is unwanted
struct TypedVar {
    var_name: Ident,
    colon_token: Colon,
    var_type: Type
}

impl Parse for TypedVar {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let var_name: Ident = input.parse()?;
        let colon_token: Colon = input.parse::<Token![:]>()?;
        let var_type: Type = input.parse()?;
        Ok(TypedVar{var_name,colon_token,var_type})
    }
}

impl ToTokens for TypedVar {
    fn to_tokens(&self, tokens: &mut TokenStream2) {
        self.var_name.to_tokens(tokens);
        self.colon_token.to_tokens(tokens);
        self.var_type.to_tokens(tokens);
    }
}

// var whose type can be inferred
struct OptTypedVar {
    var_name: Ident,
    colon_token: Option<Colon>,
    var_type: Option<Type>
}

impl Parse for OptTypedVar {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let var_name: Ident = input.parse()?;
        let mut colon_token: Option<Colon> = None;
        let mut var_type: Option<Type> = None;

        let lookahead = input.lookahead1();
        if lookahead.peek(Token![:]) {
            colon_token = Some(input.parse::<Colon>()?);
            var_type = Some(input.parse()?);
        }
        Ok(OptTypedVar{var_name,colon_token,var_type})
    }
}

impl ToTokens for OptTypedVar {
    fn to_tokens(&self, tokens: &mut TokenStream2) {
        self.var_name.to_tokens(tokens);
        self.colon_token.to_tokens(tokens);
        self.var_type.to_tokens(tokens);
    }
}


struct VarAssign {
    maybe_mut_token: Option<Mut>,
    var_name: Ident,
    assign_token: Token![=],
    init_expr: Expr
}

impl Parse for VarAssign {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let lookahead = input.lookahead1();
        let maybe_mut_token = if lookahead.peek(Mut) { Some(input.parse::<Mut>()?) } else { None };
        let var_name: Ident = input.parse()?;
        let assign_token: Token![=] = input.parse()?;
        let init_expr: Expr = input.parse()?;

        Ok(VarAssign{ maybe_mut_token, var_name, assign_token, init_expr })
    }
}

impl ToTokens for VarAssign {
    fn to_tokens(&self, tokens: &mut TokenStream2) {
        if let Some(t) = self.maybe_mut_token { t.to_tokens(tokens) }
        self.var_name.to_tokens(tokens);
        self.assign_token.to_tokens(tokens);
        self.init_expr.to_tokens(tokens);
    }
}

fn get_type_base (t: &Type)->TokenStream2 {
    match t {
        Type::Reference(ref type_reference) => { let elem = &type_reference.elem; quote!{ #elem } }
        _ => quote!{ #t } // TODO - there are probably Type variants we have to reject
    }
}

const N_SYS_MSGS: usize = 7;
const SYS_MSGS: [&'static str; N_SYS_MSGS] = [
    "_Start_", "_Ping_", "_Timer_", "_Exec_", "_Pause_", "_Resume_", "_Terminate_"
];

fn get_sys_msg_idents()->[Ident;N_SYS_MSGS] {
    SYS_MSGS.map( |i| { Ident::new(i, Span::call_site())})
}

fn get_sys_msg_types()->[Path;N_SYS_MSGS] {
    SYS_MSGS.map( |i| { 
        let ident = Ident::new(i, Span::call_site());
        let mut segments = Punctuated::new();
        segments.push( PathSegment { ident, arguments: syn::PathArguments::None } );
        Path { leading_colon: None, segments}
    })
}

fn expand_msg_match_action (ts: TokenStream, ret_val: TokenStream2)->TokenStream {
    let body = TokenStream2::from(ts); // we need a TokenStream2 to get a ToToken impl
    let new_item: TokenStream =quote! {
        { { #body }; #ret_val }
    }.into();

    new_item
}

fn variant_spec_to_string (var_spec: &VariantSpec)->String {
    match var_spec {
        VariantSpec::Type(path) => {
            let ts: TokenStream = quote! { #path }.into(); // ..a bit lazy
            ts.to_string()
        }
        VariantSpec::Wildcard => {
            "_".to_string()
        }
    }
}

fn path_to_string (path: &Path)->String {
    let ts: TokenStream = quote! { #path }.into(); // ..a bit lazy
    ts.to_string()
}

/// turn a type (Path) into a valid Ident string
/// Note this does not need to be reversible since our macros only use valid type strings as
/// input and the mangled name is never seen be the user. 
/// The mapping only needs to be locally unique, i.e. it should not collide with a user-provided
/// type. For that reason the mapping should not use any commonly used chars but still produce
/// reasonably readable Debug output. 
/// symmetric candidates:
///     Ͼ \u{03fe} , Ͽ \u{03ff}
///     ᄼ \u{113c} , ᄾ \u{113e}
///     ᐊ \u{140a} , ᐅ \u{1405} 
///     ᐸ \u{1438} , ᐳ \u{1433}
///     ᑕ \u{1455} , ᑐ \u{1450}
///     ʕ \u{0295} , ʔ \u{0294}
///     ʃ \u{0283} , ʅ \u{0285}
///     𐅁 \u{10141} ,  𐅀 \u{10140}
///     𛰙 \u{1bc19} ,  𛰚 \u{1bc1a}
///     𑄼 \u{1113c}
///     𖫫 \u{16aeb}
///     
/// Candidates from https://util.unicode.org/UnicodeJsps/list-unicodeset.jsp?a=[:XID_Continue=Yes:]
fn mangle (s: &str)->String {
    let mut r = String::with_capacity(s.len());
    let mut lifetime = false;
    for c in s.chars() {
        match c {
            ':' => r.push('\u{02d0}'), // 'ː'
            '<' => r.push('\u{1438}'), // 'ᐸ' 
            '>' => r.push('\u{1433}'), // 'ᐳ'
            ',' => r.push('\u{02ce}'), // 'ˎ'
            '(' => r.push('\u{1bc19}'), // '𛰙'
            ')' => r.push('\u{1bc1a}'), // '𛰚'
            '&' => r.push('\u{1113c}'), // '𑄼'
            '\'' => { lifetime = true; r.push('\u{02bd}') }, // 'ʽ'
            ' ' => if lifetime { lifetime = false; r.push('\u{02d1}') }, // 'ˑ'
            _ => r.push(c)
        }
    }
    r
}

// this can be used for pseudo keywords
fn parse_ident_value (input: ParseStream<'_>, expected: &str)->syn::Result<()> {
    let ident: Ident = input.parse()?;
    if ident != expected {
        Err( syn::Error::new(ident.span(), format!("expected `{}`", expected)))
    } else {
        Ok(())
    }
}

fn parse_visibility (input: ParseStream) -> Visibility {
    let lookahead = input.lookahead1();
    if lookahead.peek(Token![pub]) {
        input.parse::<Visibility>().unwrap()
    } else {
        Visibility::Inherited
    }
} 

/* #endregion support funcs */