//! declarative macros used/exported by odin_actor

/// syntactic sugar for a block returning a [`ReceiveAction::Continue`]
/// normally used from within [`match_actor_msg`] macro invocations
/// 
/// Example
/// ```
/// cont! { println!("go on") }
/// ```
/// gets expanded into
/// ```
/// { {println!("go on");} ReceiveAction::Continue }
/// ```
#[macro_export]
macro_rules! cont {
    { $( $s:stmt )* } => { {{$( $s; )*} ; ReceiveAction::Continue} }
}

/// syntactic sugar for a block returning a [`ReceiveAction::Stop`]
/// normally used from within [`match_actor_msg`] macro invocations
#[macro_export]
macro_rules! stop {
    { $( $s:stmt )* } => { {{$( $s; )*} ; ReceiveAction::Stop} }
}

/// syntactic sugar for a block returning a [`ReceiveAction::Stop`]
/// normally used from within [`match_actor_msg`] macro invocations
#[macro_export]
macro_rules! request_termination {
    { $( $s:stmt )* } => { {{$( $s; )*} ; ReceiveAction::RequestTermination} }
}

/// syntactic sugar for message match expressions within Actor::receive(..) method impls
/// the main purpose is to hide the wrapper variants of the MsgType enums
/// While the ReceiveAction return type of match arms can be explicitly set by match expressions
/// it is normally set by using the [`cont`] and [`stop`] macros
/// 
/// Example
/// ```
/// match_actor_msg! { msg: GreeterMsg as
///     Greet => cont! { println!("hello {}!", msg.whom) }
///     _Terminate_ => stop! { println!("stopped") }
/// }
/// ```
/// gets expanded into:
/// ```
/// impl Actor<GreetMsgs> for Greeter {
/// async fn receive (&mut self, msg: GreeterMsg, hself: &ActorHandle<GreeterMsg>)->ReceiveAction {
///     use GreeterMsg::*; 
///     match msg {
///         Greet(msg) => {
///             println!("hello {}!", msg.whom);
///             ReceiveAction::Continue
///         }
///         _Terminate_ => {
///             println!("hello {}!", msg.whom);
///             ReceiveAction::Stop
///         }
///         _ => msg.default_receive_action()            
///     }
/// }
/// ```
#[macro_export]
macro_rules! match_actor_msg {
    {$msg:ident : $msg_type:ident as $( $variant:ident => $blk:expr )* } =>
    {
        match $msg {
            $( $msg_type::$variant ($msg) => $blk ),* ,
            _ => $msg . default_receive_action()
        } 
    }
}
