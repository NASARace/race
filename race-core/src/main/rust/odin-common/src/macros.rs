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
///     Some(a) = { p },         // binds 'a'
///     Ok(b) = { foo(a) } => {  // not evaluated of first match clause fails
///         println!("a={}, b={}", a,b);
///     }
/// };
///
/// // expression with per-clause short-circuits
/// let r = if_let! {
///     Some(a) = { p.or(q) } ? { println!("neither p nor q"); 1 },
///     Ok(b)   = { foo(a) }  ? { println!("wrong result"); 2 } => {
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
macro_rules! if_let {
    {$p:pat = $x:block $(? $e:block)? => $r:block} =>
    { if let $p=$x $r $(else $e)? };

    {$p1:pat = $x1:block  $(? $e1:block)?,
     $p2:pat = $x2:block  $(? $e2:block)? => $r:block}  =>
    { if let $p1=$x1 {
        if let $p2=$x2 $r $(else $e2)?
      } $(else $e1)?
    };

    {$p1:pat = $x1:block  $(? $e1:block)?,
     $p2:pat = $x2:block  $(? $e2:block)?,
     $p3:pat = $x3:block  $(? $e3:block)? => $r:block}  =>
    { if let $p1=$x1 {
        if let $p2=$x2 {
            if let $p3=$x3 $r $(else $e3)?
        } $(else $e2)?
      } $(else $e1)?
    };

    {$p1:pat = $x1:block  $(? $e1:block)?,
     $p2:pat = $x2:block  $(? $e2:block)?,
     $p3:pat = $x3:block  $(? $e3:block)?,
     $p4:pat = $x4:block  $(? $e4:block)? => $r:block}  =>
    { if let $p1=$x1 {
        if let $p2=$x2 {
            if let $p3=$x3 {
                if let $p4=$x4 $r $(else $e4)?
            } $(else $e3)?
        } $(else $e2)?
      } $(else $e1)?
    };

    {$p1:pat = $x1:block  $(? $e1:block)?,
     $p2:pat = $x2:block  $(? $e2:block)?,
     $p3:pat = $x3:block  $(? $e3:block)?,
     $p4:pat = $x4:block  $(? $e4:block)?,
     $p5:pat = $x5:block  $(? $e5:block)? => $r:block}  =>
    { if let $p1=$x1 {
        if let $p2=$x2 {
            if let $p3=$x3 {
                if let $p4=$x4 {
                    if let $p5=$x5 $r $(else $e5)?
                } $(else $e4)?
            } $(else $e3)?
        } $(else $e2)?
      } $(else $e1)?
    };
  //... and possibly more to follow
}
