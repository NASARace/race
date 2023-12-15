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
	TokenStream as TokenStream2,
    Spacing, Span, Punct
};
use quote::{quote,format_ident,ToTokens};
use syn::{ 
	self,Ident,Path,ItemStruct,ItemEnum,ItemFn,FnArg,Token,Type,TypePath,Block,ExprMacro,Constraint,
    parse_macro_input,
    punctuated::{Punctuated},
    parse::{Parse,ParseStream,Result}, token, ExprMethodCall, PathSegment, Stmt, Visibility,
};
use std::collections::HashSet;

/* #region define macros *********************************************************/

/// macro to define algebraic types (using a Haskell'ish syntax), which are mapped into enums
/// whose variant names are transparent (automatically generated from element types).
/// 
/// Note the variant names are never supposed to be presented to the user if respective 
/// enum values are matched with the corresponding [`match_algebraic_type`] macro
/// 
/// Note: if message variants use path types (e.g. `std::vec::Vec`) the same notation
/// has to be used in both [`define_algebraic_type`] and [`match_algebraic_type`] 
/// 
/// Example:
/// ```
/// define_algebraic_type! {
///     pub MyMsg = A | B<std::vec::Vec<(u32,&'static str)>>
/// }
/// ```
/// This is expanded into
/// ```
/// #[derive(Debug)]
/// pub enum MyMsg {
///     A (A),
///     BʕstdːːvecːːVecʕʃu32ˎᴿʽstaticˑstrʅʔʔ (B<std::vec::Vec<(u32,&'static str)>>),
/// }
/// impl From<A> for MyMsg {...}
/// impl From<B<std::vec::Vec<(u32,&'static str)>>> for MyMsg {...}
/// ```
#[proc_macro]
pub fn define_algebraic_type (item: TokenStream) -> TokenStream {
    let MsgEnum {visibility, name, variant_types }= syn::parse(item).unwrap();
    let mut variant_names = get_variant_names_from_types(&variant_types);

    let new_item: TokenStream = quote! {
        #visibility enum #name {
            #( #variant_names ( #variant_types ) ),*
        }
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
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

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
    let MsgEnum {visibility, name, mut variant_types }= syn::parse(item).unwrap();
    for var_type in get_sys_msg_types() {
        variant_types.push(var_type)
    }

    let mut variant_names = get_variant_names_from_types(&variant_types);
    for var_name in get_sys_msg_idents() {
        variant_names.push(var_name)
    }

    let new_item: TokenStream = quote! {
        #visibility enum #name {
            #( #variant_names ( #variant_types ) ),*
        }
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
                    //#name::_Ping_(msg) => { msg.store_response(); ReceiveAction::Continue }
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
struct MsgEnum {
    visibility: Visibility,
    name: Ident,
    variant_types: Vec<Path>
}

impl Parse for MsgEnum {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let visibility: Visibility = parse_visibility(input);
        let name: Ident = input.parse()?;
        let _: Token![=] = input.parse()?;
        let variant_types = Punctuated::<Path,Token![|]>::parse_terminated(input)?;
        let variant_types = variant_types.into_iter().collect();
        
        Ok( MsgEnum { visibility, name, variant_types })
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

/* #endregion define macros */

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
    let MsgMatch { msg_name, msg_type, match_arms }: MsgMatch = syn::parse(item).unwrap();
    let variant_names: Vec<Ident> = get_variant_names_from_match_arms(&match_arms);
    let match_actions: Vec<&MsgMatchAction> = match_arms.iter().map( |a| { &a.match_action }).collect();

    let new_item: TokenStream = quote! {
        match #msg_name {
            #(
                #msg_type::#variant_names (#msg_name) => #match_actions
            ),*
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
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
///        _Terminate_(msg) => { {println!("actor terminated", msg)}; ReceiveAction::Stop }
///        _ => msg.default_receive_action()
///     }
/// ```
/// 
#[proc_macro]
pub fn match_actor_msg (item: TokenStream)->TokenStream {
    let MsgMatch { msg_name, msg_type, match_arms }: MsgMatch = syn::parse(item).unwrap();
    let variant_names: Vec<Ident> = get_variant_names_from_match_arms(&match_arms);
    let match_actions: Vec<&MsgMatchAction> = match_arms.iter().map( |a| { &a.match_action }).collect();

    let new_item: TokenStream = quote! {
        match #msg_name {
            #( #msg_type::#variant_names (#msg_name) => #match_actions, )*
            _ => #msg_name . default_receive_action()
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

fn get_variant_names_from_match_arms (match_arms: &Vec<MsgMatchArm>)->Vec<Ident> {
    match_arms.iter().map( |a| {
        let ps = path_to_string( &a.variant_type);
        let ps_mangled = mangle(ps.as_str());
        Ident::new( &ps_mangled.as_str(), Span::call_site())
    }).collect()
}

struct MsgMatch {
    msg_name: Ident, // the msg variable name to bind
    msg_type: Path, // the msg type to match
    match_arms: Vec<MsgMatchArm>
}

struct MsgMatchArm {
    variant_type: Path,
    match_action: MsgMatchAction,
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

fn parse_match_arms (input: ParseStream)->Result<Vec::<MsgMatchArm>> {
    let mut match_arms = Vec::<MsgMatchArm>::new();
    
    while !input.is_empty() {
        let variant_type: Path = input.parse()?;
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

        match_arms.push( MsgMatchArm { variant_type, match_action } );
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
/// define_actor! { match msg: ResponderMsg for Responder as
///     Ask<Question,Answer> => ...
/// }
/// ```
#[proc_macro]
pub fn impl_actor (item: TokenStream) -> TokenStream {
    let ActorReceive { msg_name, msg_type, state_type, state_constraints, match_arms }: ActorReceive = syn::parse(item).unwrap();
    let variant_names: Vec<Ident> = get_variant_names_from_match_arms(&match_arms);
    let match_actions: Vec<&MsgMatchAction> = match_arms.iter().map( |a| { &a.match_action }).collect();
    let constraint_names = collect_typevar_names( &state_constraints);

    let opt_constraints: TokenStream2 = if state_constraints.is_empty() { quote! {} } else {
        quote! { where #( #state_constraints ),* }
    };
    let opt_constraint_names: TokenStream2 = if constraint_names.is_empty() { quote! {} } else {
        quote! { < #( #constraint_names ),* > }
    };

    let new_item: TokenStream = quote! {
        impl #opt_constraint_names ActorReceiver<#msg_type> for Actor<#state_type,#msg_type> #opt_constraints {
            async fn receive (&mut self, msg: #msg_type)->ReceiveAction {
                match #msg_name {
                    #( #msg_type::#variant_names (#msg_name) => #match_actions, )*
                    _ => #msg_name . default_receive_action()
                }
            }
        }
    }.into();
    //println!("-----\n{}\n-----", new_item.to_string());

    new_item
}

struct ActorReceive {
    msg_name: Ident,
    msg_type: Path,
    state_type: Path,
    state_constraints: Vec<Constraint>, 
    match_arms: Vec<MsgMatchArm>
} 

impl Parse for ActorReceive {
    fn parse(input: ParseStream<'_>) -> syn::Result<Self> {
        let _: Token![match] = input.parse()?;
        let msg_name: Ident = input.parse()?;
        let _: Token![:] = input.parse()?;
        let msg_type: Path = input.parse()?;
        let _: Token![for] = input.parse()?;
        let state_type: Path = input.parse()?;
        let state_constraints: Vec<Constraint> = parse_constraints( input)?;
        let _: Token![as] = input.parse()?;

        let match_arms = parse_match_arms(input)?;

        Ok( ActorReceive { msg_name, msg_type, state_type, state_constraints, match_arms } )
    }
}

// unfortunately we have to do this explicitly since Punctuated is eager, assuming the rest 
// of the ParseStream only consists of its elements (which is not the case here)
fn parse_constraints (input: ParseStream<'_>) -> syn::Result<Vec<Constraint>> {
    let mut constraints = Vec::new();

    let lookahead = input.lookahead1();
    if lookahead.peek( Token![where]) {
        let _: Token![where] = input.parse()?;

        loop {
            let c: Constraint = input.parse()?;
            constraints.push(c);

            let lookahead = input.lookahead1();
            if lookahead.peek( Token![,]) {
                let _: Token![,] = input.parse()?;
            } else {
                break
            }
        }
    } 

    Ok(constraints)
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

/* #region support funcs *********************************************************/

const SYS_MSGS: [&'static str; 6] = [
    "_Start_", "_Ping_", "_Timer_", "_Pause_", "_Resume_", "_Terminate_"
];

fn get_sys_msg_idents()->[Ident;6] {
    SYS_MSGS.map( |i| { Ident::new(i, Span::call_site())})
}

fn get_sys_msg_types()->[Path;6] {
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
/// Candidates from https://util.unicode.org/UnicodeJsps/list-unicodeset.jsp?a=[:XID_Continue=Yes:]
fn mangle (s: &str)->String {
    let mut r = String::with_capacity(s.len());
    let mut lifetime = false;
    for c in s.chars() {
        match c {
            ':' => r.push('\u{02d0}'), // 'ː'
            '<' => r.push('\u{0295}'), // 'ʕ' 
            '>' => r.push('\u{0294}'), // 'ʔ'
            ',' => r.push('\u{02ce}'), // 'ˎ'
            '(' => r.push('\u{0283}'), // 'ʃ'
            ')' => r.push('\u{0285}'), // 'ʅ'
            '&' => r.push('\u{1d3f}'), // 'ᴿ'
            '\'' => { lifetime = true; r.push('\u{02bd}') }, // 'ʽ'
            ' ' => if lifetime { lifetime = false; r.push('\u{02d1}') }, // 'ˑ'
            _ => r.push(c)
        }
    }
    r
}

fn parse_ident_value (input: ParseStream<'_>, expected: &str)->syn::Result<()> {
    let ident: Ident = input.parse()?;
    if ident != expected {
        Err( syn::Error::new(ident.span(), format!("expected `{}`", expected)))
    } else {
        Ok(())
    }
}

fn collect_typevar_names<'a> (constraints: &'a Vec<Constraint>) -> Vec<&'a Ident> {
    if constraints.is_empty() {
        Vec::new()
    } else {
        constraints.into_iter().map( |c| &c.ident ).collect()
    }
}

/* #endregion support funcs */