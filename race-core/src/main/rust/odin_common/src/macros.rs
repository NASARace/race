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

#[allow(unused_macros)]

/// macro to flatten deeply nested "if let .." trees into a construct akin to Scala for-comprehensions
/// (or Haskell do-notation) with the extension that we can (optionally) specify side effects and/or
/// return values for failed match clauses.
///
/// # Examples
/// ```
/// use odin_common::macros;
/// ..
/// let p: Option<i64> = Some(42);
/// let q: Option<i64> = None;
/// fn foo (i: i64) -> Result<String,SomeError> {..}
/// ...
/// // purely for side effects if all matches succeed
/// if_let! {
///     Some(a) = p;         // binds 'a'
///     Ok(b) = foo(a) => {  // not evaluated if first match clause fails
///         println!("a={}, b={}", a,b);
///     }
/// };
///
/// // expression with per-clause short-circuits
/// let r = if_let! {
///     Some(a) = p.or(q) , { println!("neither p nor q"); 1 };
///     Ok(b)   = foo(a)  , { println!("wrong result"); 2 } => {
///         println!("a={}, b={}", a,b); 3
///     }
///  };
/// ```
/// this expands to:
/// ```
/// let r = if let Some(a) = p.or(q) {
///     if let Ok(b) = foo(a) {
///         println!("a={}, b={}", a,b); 3
///     } else {
///         println!("wrong result"); 2
///     }
/// } else {
///     println!("neither p nor q"); 1
/// }
/// ```
#[macro_export]
macro_rules! if_let {
    { $p:pat = $x:expr $(, $e:expr)? => $r:expr } =>
    {
        if let $p = $x { $r } $(else { $e })?
    };

    { $p:pat = $x:expr , $i:ident => $e:expr => $r:expr } =>
    {
        match $x {
            $p => { $r }
            $i => { $e }
        }
    };

    { $p:pat = $x:expr $(, $e:expr)? ; $($ts:tt)+ } =>
    {
        if let $p = $x {
            if_let! { $($ts)+ }
        } $(else { $e })?
    };

    { $p:pat = $x:expr , $i:ident => $e:expr ; $($ts:tt)+ } =>
    {
        match $x {
            $p => { if_let! { $($ts)+ } }
            $i => { $e }
        }
    }
}
pub use if_let; // preserve 'macros' module across crates

/// syntactic sugar for "format!(...).as_str()" - can only be used for arguments, not to bind variables
#[macro_export]
macro_rules! str {
    ( $fmt:literal, $($arg:expr),* ) =>
    {
        format!($fmt,$($arg),*).as_str()
    }
}
pub use str;

#[macro_export]
macro_rules! max {
    ($x:expr) => ( $x );
    ($x:expr, $($xs:expr),+) => {
        $x.max( max!( $($xs),+))
    };
}
pub use max;

#[macro_export]
macro_rules! min {
    ($x:expr) => ( $x );
    ($x:expr, $($xs:expr),+) => {
        $x.min( min!( $($xs),+))
    };
}
pub use min;

/// macros to reduce boilerplate for coercing errors into custom lib errors.
/// This is a simplistic alternative to the 'thiserror' crate. It is aimed at providing
/// specific, matchable error types for lib crates, optionally preserving underlying error chains
/// and keeping code clutter minimal.
///
/// The downside is that in it's current form it only provides a fixed, non-extensible error structure
///
/// single type use case is like so:
/// ```
/// define_err!(BarError);
///
/// fn bar (mut s: String) -> Result<(),BarError> {
///     map_err!( s.try_reserve(42) => BarError{"could not extend string capacity"})?;
///     Ok(())
/// }
///
/// fn main () {
///    match bar("somewhat".to_string()) {
//         Ok(()) => println!("bar Ok"),
//         Err(e) => println!("bar error = {}", e)
//     }
/// }
/// ```
///
/// error enums can be defined/used like so:
/// ```
/// define_err!(FooError: FooInputError,FooOutputError);
///
/// fn foo (bs: &[u8]) -> Result<String,FooError> {
///     use FooError::*;
///     // let cs = CString::new(bs).map_err(|e| FooInputError{source:Box(e),msg: format!("0-byte in input: {:?}",bs))?;
///     // let s = cs.into_string().map_err(|e| FooInputError{source:Box(e),msg: format!("malformet utf8")?;
///
///     let cs = map_err!( CString::new(bs) => FooInputError{"0-byte in input: {:?}", bs})?;
///     let s = map_err!( cs.into_string() => FooInputError{"malformed utf8"})?;
///     return_err!( s.len() > 8 => FooOutputError{"output too long: {}", bs.len()});
///
///     Ok(s)
/// }
///
/// fn main () {
///     use FooError::*;
///     let bs: &[u8] = &[104, 101, 0, 108, 108, 111];
///
///     match foo(bs) {
///        Ok(s) => println!("Ok(s) = {}", s),
///        Err(ref e) => match e {
///          FooInputError{src,msg} => println!("foo input error = {}", e),
///          FooOutputError{src,msg} => println!("foo output error = {}", e)
///        }
///     }
/// ```


#[macro_export]
macro_rules! define_err {
    ($t:ident) =>
    { #[derive(Debug)]
      pub struct $t { src: Option<Box<dyn Error + Send + Sync + 'static>>, msg: Option<String> }
      impl Error for $t {
          fn source(&self) -> Option<&(dyn Error + 'static)> {
             match &self.src {
                 Some(b) => Some(b.as_ref()),
                 None => None
             }
          }
      }
      impl fmt::Display for $t {
          fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
            match self.msg {
                Some(ref msg) => write!( f, "{}: {}", stringify!($t), msg),
                None => write!( f, "{}", stringify!($t))
            }
          }
      }
    };

    ($t:ident : $($m:ident),+) =>
    {   #[derive(Debug)]
        pub enum $t {
            $($m { src: Option<Box<dyn Error + Send + Sync + 'static>>, msg: Option<String> },)+
        }
        impl Error for $t {
            fn source(&self) -> Option<&(dyn Error + 'static)> {
                use $t::*;
                match *self {
                    $(
                      $m{src:ref src,msg:_} => match src {
                          Some(b) => Some(b.as_ref()),
                          None => None
                      }
                    )+
                }
            }
        }
        impl fmt::Display for $t {
          fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
            use $t::*;
            match *self {
                $(
                  $m{src:_,msg:ref msg} => match msg {
                    Some(ref msg) => write!( f, "{}::(): {}", stringify!($t),stringify!($m), msg),
                    None => write!( f, "{}::{}", stringify!($t),stringify!($t)),
                  }
                )+
            }
          }
        }
    };
}
pub use define_err;

#[macro_export]
macro_rules! return_err {
    ($e:expr => $t:ident ) =>
    { if $e { return Err($t{ src:None, msg:None}) } };

    ($e:expr => $t:ident { $f:literal $(, $p:expr)* } ) =>
    { if $e { return Err($t{ src:None, msg:Some(format!($f $(,$p)* ))}) } };
}
pub use return_err;

#[macro_export]
macro_rules! map_err {
    ($e:expr => $t:ident ) =>
    { $e.map_err(|err| $t{ src:Some(Box::new(err)), msg:None}) };

    ($e:expr => $t:ident { $f:literal $(, $p:expr)* } ) =>
    { $e.map_err(|err| $t{ src:Some(Box::new(err)), msg:Some(format!($f $(,$p)* ))}) };
}
pub use map_err;

#[macro_export]
macro_rules! io_error {
    ( $kind:expr, $fmt:literal, $($arg:expr)* ) =>
    {
        io::Error::new( $kind, format!($fmt,$($arg),*).as_str())
    }
}
pub use io_error;