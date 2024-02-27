
use odin_macro::fn_mut;

#[test]
fn test_fnmut() {
    let foo = "foo".to_string();

    let mut f1 = fn_mut!{
        (mut foo=foo.clone(), b=foo.len()) => |x: usize| {
            foo.push_str(" boo");
            println!("f1: {foo} from {b} and {x:?}");
        }
    };
    f1(42);
    
    let f2 = fn_mut!( (foo=foo.clone()) => println!("f2: {foo}"));
    f2();
    
    let f3 = fn_mut!( |a| println!("f3: {a:?}"));
    f3(&foo);
    
    let f4 = fn_mut!{ println!("f4: {foo}")};
    f4();
}