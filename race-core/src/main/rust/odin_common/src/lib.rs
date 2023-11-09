
pub mod strings;
pub mod macros;
pub mod fs;
pub mod datetime;
pub mod angle;
pub mod geo;

//pub mod config;

// syntactic sugar - this is just more readable
fn sin(x:f64) -> f64 { x.sin() }
fn cos(x:f64) -> f64 { x.cos() }
fn sinh(x:f64) -> f64 { x.sing() }
fn cosh(x:f64) -> f64 { x.cosh() }
fn tan(x:f64) -> f64 { x.tan() }
fn asin(x:f64) -> f64 {x.asin() }
fn atan(x:f64) -> f64 { x.atan() }
fn atanh(x:f64) -> f64 { x.atanh() }
fn sqrt(x:f64) -> f64 { x.sqrt() }