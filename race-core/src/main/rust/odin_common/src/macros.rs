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
/// // purely for side effects
/// if_let! {
///     Some(a) = p;         // binds 'a'
///     Ok(b) = foo(a) => {  // not evaluated of first match clause fails
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
macro_rules! io_error {
    ( $kind:expr, $fmt:literal, $($arg:expr)* ) =>
    {
        io::Error::new( $kind, format!($fmt,$($arg),*).as_str())
    }
}
pub use io_error;