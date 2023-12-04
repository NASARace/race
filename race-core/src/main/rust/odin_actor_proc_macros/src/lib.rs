#![allow(unused)]

extern crate proc_macro;

use proc_macro::TokenStream;
use proc_macro2::{
	TokenStream as TokenStream2,
    Spacing, Span, Punct
};
use quote::{quote,format_ident,ToTokens};
use syn::{self, ReturnType};
use syn::{ 
	Ident,Path,ItemStruct,ItemEnum,ItemFn,FnArg,Token,Type,TypePath,
    parse_macro_input,
    punctuated::{Punctuated},
    parse::{Parse,ParseStream,Result},
};

#[proc_macro]
pub fn define_typeset (item: TokenStream) -> TokenStream {
    use syn::{DataEnum,Variant};

    let ast: ItemEnum = syn::parse(item).unwrap();
    let enum_ident = ast.ident;
    let enum_vis = ast.vis;
    let var_type: Vec<Ident> = ast.variants.iter().map(|v| v.ident.clone()).collect();
    //let var_name: Vec<Ident> = var_type.iter().map(|v| format_ident!("{}_",v)).collect();
    let var_name = &var_type;

    let new_item: TokenStream = quote! {
        #[derive(Debug)]
        #enum_vis enum #enum_ident { 
            #( #var_name ( #var_type ) ),*
        }
        #(
            impl From<#var_type> for #enum_ident {
                fn from (o: #var_type) -> #enum_ident { #enum_ident::#var_name(o) }
            }
        )*
    }.into();

    new_item
}

#[proc_macro]
pub fn define_actor_msg_set (item: TokenStream) -> TokenStream {
    use syn::{DataEnum,Variant};

    let ast: ItemEnum = syn::parse(item).unwrap();
    let enum_ident = ast.ident;
    let enum_vis = ast.vis;

    let mut sys_var: Vec<Ident> = Vec::new();
    sys_var.push(format_ident!("_Start_"));
    sys_var.push(format_ident!("_Ping_"));
    sys_var.push(format_ident!("_Timer_"));
    sys_var.push(format_ident!("_Pause_"));
    sys_var.push(format_ident!("_Resume_"));
    sys_var.push(format_ident!("_Terminate_"));

    let mut var_type: Vec<Ident> = ast.variants.iter().map(|v| v.ident.clone()).collect();
    var_type.append(&mut sys_var);

    //let var_name: Vec<Ident> = var_type.iter().map(|v| format_ident!("{}_",v)).collect();
    let var_name = &var_type;

    // TODO - should we check if the user already listed some of the system messages?
    let new_item: TokenStream = quote! {
        #enum_vis enum #enum_ident { 
            #( #var_name ( #var_type ), )*
        }
        #(
            impl From<#var_type> for #enum_ident {
                fn from (o: #var_type)->Self { #enum_ident::#var_name(o) }
            }
        )*
        impl FromSysMsg for #enum_ident {}

        impl std::fmt::Debug for #enum_ident {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                match self {
                    #( #enum_ident::#var_name (msg) => write!(f, "{:?}", msg), )*
                }
            }
        }

        impl DefaultReceiveAction for #enum_ident {
            fn default_receive_action (&self)->ReceiveAction {
                match self {
                    #enum_ident::_Terminate_(msg) => ReceiveAction::Stop,
                    #enum_ident::_Ping_(msg) => { msg.store_response(); ReceiveAction::Continue }
                    _ => ReceiveAction::Continue
                }
            }
        }
    }.into();
    //println!("----\n{}\n----", new_item.to_string());

    new_item
}


#[proc_macro_attribute]
pub fn impl_actor (attr: TokenStream, item: TokenStream) -> TokenStream {
    use syn::ItemImpl;
    let actor = parse_macro_input!(attr as AttrArg).arg;
    let ast: ItemFn = syn::parse(item).unwrap();
    let sig = &ast.sig;
    let actor_handle = format_ident!("{}Handle", actor);
    
    // check if fn name is 'receive'
    let fn_name = &sig.ident;
    if fn_name != "receive" { panic!("wrong actor impl method name, must be 'receive'") }

    if sig.asyncness.is_none() { panic!("receive(..) must be async") }

    if let ReturnType::Type(_,box_type) = &sig.output {
        if let Type::Path(TypePath{qself,path}) = box_type.as_ref() {
            if let Some(ret_type) = path.get_ident() {
                if ret_type != "ReceiveAction" { panic!("receive() must return ReceiveAction") }
            } else { panic!("receive() must return ReceiveAction") }
        } else { panic!("receive() must return ReceiveAction") }
    } else { panic!("receive() must return ReceiveAction") }

    if sig.inputs.len() != 4 { panic!("wrong method signature, must be (&mut self,MsgType,&ActorHandle<MsgType>,&ActorSys)") }

    let args: Vec<&FnArg> = sig.inputs.iter().collect();
    if let FnArg::Receiver(rec_arg) = args[0] {
        if rec_arg.mutability.is_none() { panic!("wrong 1st argument, must be '&mut self'") }
    } else { panic!("wrong 1st argument, must be '&mut self'") };

    let msg_type = if let FnArg::Typed(msg_arg) = args[1] { // msg_arg is a Box
        if let Type::Path(TypePath{qself,path}) = &msg_arg.ty.as_ref() {
            if let Some(msg_type) = path.get_ident() {
                msg_type
            } else { panic!("wrong 2nd argument, must be message type") }
        } else { panic!("wrong 2nd argument, must be message type") }
    } else { panic!("wrong 2nd argument, must be message type") };

    // TODO - should also check 3rd arg type, which should be ActorHandle<MsgType>

    let new_item: TokenStream = quote! {
        impl Actor<#msg_type> for #actor {
            #ast
        }
        pub type #actor_handle = ActorHandle<#msg_type>;
    }.into();
    //println!("----\n{}\n----", new_item.to_string());

    new_item
}

//--- parse utilities

fn last_type_ident (ty: &syn::Type) -> Ident {
    if let syn::Type::Path(ref tp) = ty {
        if let Some(last_elem) = tp.path.segments.last() {
            last_elem.ident.clone()
        } else { panic!("not a valid type") }
    } else { panic!("not a valid type") }
}

#[derive(Debug)]
struct AttrArgs {
  args: Vec<Ident>
}

impl Parse for AttrArgs {
    fn parse (input: ParseStream) -> Result<Self> {
    	let args = Punctuated::<Ident,Token![,]>::parse_terminated(input)?;
    	let args: Vec<Ident> = args.into_iter().collect();
    	Ok( AttrArgs{args} )
    }
}

impl ToTokens for AttrArgs {
	fn to_tokens(&self, tokens: &mut TokenStream2) {
		let mut is_first = true;
		for a in &self.args {
            if !is_first { 
				Punct::new(',', Spacing::Joint).to_tokens(tokens);
            } else {
				is_first = false;
            }
			a.to_tokens(tokens);
		}
    }
}

#[derive(Debug)]
struct AttrArg {
    arg: Ident
}

impl Parse for AttrArg {
    fn parse (input: ParseStream) -> Result<Self> {
    	let args = Punctuated::<Ident,Token![,]>::parse_terminated(input)?;
    	let args: Vec<Ident> = args.into_iter().collect();
        if args.len() > 1 {
            Err( input.error("expected single ident argument") )
		} else {
            let arg = args[0].clone();
    		Ok( AttrArg{arg} )
		}
    }
}

impl ToTokens for AttrArg {
    fn to_tokens(&self, tokens: &mut TokenStream2) {
       self.arg.to_tokens(tokens);
    }
}


// how to parse   Producer<Client> where Client: MsgReceiver<MyMsg> + Send)
// is it even worth it?