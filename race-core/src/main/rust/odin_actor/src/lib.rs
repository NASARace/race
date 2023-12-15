#![allow(unused_imports)]

use std::{
    pin::{Pin,pin}, 
    future::Future, 
    time::{Duration,Instant}, 
    fmt::Debug, 
    sync::{Arc, atomic::{AtomicU64,Ordering}},
    cmp::min, marker::PhantomData
};

pub mod prelude;

pub const DEFAULT_CHANNEL_BOUNDS: usize = 16;

//#[cfg(feature = "tokio_kanal")]
pub mod tokio_kanal;

//#[cfg(feature = "tokio_channel")]
pub mod tokio_channel;

pub mod errors;
use errors::Result;

extern crate odin_macro;
#[doc(hidden)]
pub use odin_macro::{define_actor_msg_type, match_actor_msg, cont, stop, term, impl_actor, spawn_actor};

#[inline] pub fn secs (n: u64)->Duration { Duration::from_secs(n) }
#[inline] pub fn millis (n: u64)->Duration { Duration::from_millis(n) }
#[inline] pub fn micros (n: u64)->Duration { Duration::from_micros(n) }
#[inline] pub fn nanos (n: u64)->Duration { Duration::from_nanos(n)} 


/// type that can be used for returning futures in object-safe (async) traits
pub type ObjSafeFuture<'a, T> = Pin<Box<dyn Future<Output=T> + Send + 'a>>;

/// sendable function that returns a future
pub type SendableFutureCreator = Box<dyn FnOnce() -> Pin<Box<dyn Future<Output = ()> + Send>> + Send + Sync + 'static>;

// see https://stackoverflow.com/questions/74920440/how-do-i-wrap-a-closure-which-returns-a-future-without-it-being-sync
pub fn create_sfc <F,R> (func: F) -> SendableFutureCreator
    where
        F: FnOnce() -> R  + Send + Sync + 'static,
        R: Future<Output = ()> + Send + 'static,
{
    Box::new(move || {
        Box::pin(async move {
            let fut = { func() }; 
            fut.await;
        })
    })
}


/* #region runtime/channel agnostic traits and types **************************************************************/
/*
 * note this does not include Actor and ActorHandle since they use channel/runtime specific types that would
 * have to be abstracted if we hoist them here, which would decrease readability and increase runtime cost
 */

pub enum ActorSystemRequest {
    RequestTermination,
    RequestActorOf { id: Arc<String>, type_name: &'static str, sys_msg_receiver: Box<dyn SysMsgReceiver>, sfc: SendableFutureCreator }
}

pub trait Identifiable {
    fn id(&self) -> &str;
}

/// while it can be used explicitly this trait is normally transparent and hidden behind the [`define_actor`] macro
pub trait ActorReceiver <MsgType> where MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug {
    fn receive (&mut self, msg: MsgType)-> impl Future<Output = ReceiveAction> + Send;
}

pub enum ReceiveAction {
    Continue, // continue receiving messages
    Stop,  // stop receiving messages
    RequestTermination, // ask actor system to send _Terminate_ messages
}

/// single message type receiver trait to abstract concrete ActorHandle<MsgSet> instances that would
/// force the client to know all messages the receiver understands, which reduces re-usability of the
/// receiver. Note this trait is not object-safe (use [`DynMsgReceiver`] for dynamic subscription)
pub trait MsgReceiver<MsgType>: Identifiable + Debug + Send + Clone {
    fn send_msg (&self, msg: MsgType)->impl Future<Output = Result<()>> + Send;
    fn timeout_send_msg (&self, msg: MsgType, to: Duration)->impl Future<Output = Result<()>> + Send;
    fn try_send_msg (&self, msg:MsgType)->Result<()>;
}

/// this is a single message type receiver trait that is object safe, which means its
/// async [`send_msg`] and [`timeout_send_msg`] methods return [`ObjSafeFuture`] futures
/// (`Pin<Box<dyn Future<..>>>`), hence they incur runtime cost.
/// This trait is the basis for making actors combine through publish/subscribe messages
/// Use the more efficient [`MsgReceiver`] if trait objects are not required
pub trait DynMsgReceiver<MsgType>: Identifiable + Debug + Send  {
    fn send_msg (&self, msg: MsgType) -> ObjSafeFuture<Result<()>>;
    fn timeout_send_msg (&self, msg: MsgType, to: Duration) -> ObjSafeFuture<Result<()>>;
    fn try_send_msg (&self, msg: MsgType) -> Result<()>;
}

pub type Subscriber<M> = Box<dyn DynMsgReceiver<M> + Send + Sync + 'static>;

pub fn subscriber<M> (s: impl DynMsgReceiver<M> + Send + Sync + 'static)->Subscriber<M> {
    Box::new(s)
}

/// container to keep a dynamically updated list of homogenous DynMsgReceiver instances
/// Subscriptions objects are used as fields within concrete actor structs to implement publish/subscribe
/// patterns that hide the concrete types of the subscribers (which don't even have to be actors) 
pub struct Subscriptions<M>
    where M: Send + Clone + Debug + 'static
{
    list: Vec<Subscriber<M>>, 
}

// TODO - should we automatically remove subscribers we fail to send to?
impl<M> Subscriptions<M> 
        where M: Send + Clone + Debug + 'static
{
    pub fn new()->Subscriptions<M> {
        Subscriptions { list: Vec::new() }
    }

    pub fn add (&mut self, subscriber: Subscriber<M>) {
        self.list.push( subscriber);
    }

    pub async fn publish_msg (&self, msg: M) -> Result<()> {
        for p in &self.list {
            p.send_msg( msg.clone()).await?
        }
        Ok(())
    }

    pub async fn timeout_publish_msg (&self, msg: M, to: Duration) -> Result<()> {
        for ref p in &self.list {
            p.timeout_send_msg( msg.clone(), to).await?
        }
        Ok(())
    }
}

/* #endregion runtime/channel agnostic traits and types */


/* #region sytem messages ****************************************************************/
/*
 * System messages follow a _<name>_ pattern to indicate they are (usually) not sent explicitly
 * by actor code but through the governing actor system.
 * They are not allowed to use any runtime or channel specific types.
 * Each actor MsgSet has to include all of them, which is guaranteed if the MsgSet was created
 * by our define_actor_msg_set!( ..) macro
 */

#[derive(Debug,Clone)] 
pub struct _Start_;

// does not make sense to derive Clone since the timer id is actor specific
#[derive(Debug)] 
pub struct _Timer_ { pub id: i64 }

#[derive(Debug,Clone)] 
pub struct _Pause_;

#[derive(Debug,Clone)] 
pub struct _Resume_;

#[derive(Debug,Clone)]
 pub struct _Terminate_;

/// Ping messages are the exception to the rule that actors only modify local state.
/// In order to minimize runtime costs we process them in parallel, i.e. the receiver just stores
/// the (atomic, i.e. lock-free) response and the sender does not wait for a reply.
/// This means it is up to the sender/monitor to decide wheter an actor is deemed to be un-responsive
/// and to take appropriate action.
#[derive(Debug)] 
pub struct _Ping_ { 
    /// the ping cycle of the sender
    cycle: u32, 

    /// the time when the message was sent
    sent: Instant, 

    /// this is where the receiver stores ping results as 24 bit cycle and 36 bit ns response time
    /// if the response time exceeds 36 bit it is set to the maximum
    response: Arc<AtomicU64>  
} 

pub const MAX_PING_RESPONSE: u128 = 0xFFFFFFFFFF; // 36bit means our max time after which we assume the actor is un-responsive is 68 sec

impl _Ping_ {
    pub fn store_response (&self) { // TODO - should this consume self ?
        let dt = min( (Instant::now() - self.sent).as_nanos(), MAX_PING_RESPONSE);
        let result: u64 = ((self.cycle as u64) << 24) | (dt as u64);
        self.response.store( result, Ordering::Relaxed);
    }
}

/// alias trait for something that can ge generated from system messages
pub trait FromSysMsg: From<_Start_> + From<_Ping_> + From<_Timer_> + From<_Pause_> + From<_Resume_> + From<_Terminate_> {}  

/// object-safe trait for each actor handle to send system messages
// TODO - should sent_timer() be async too?
pub trait SysMsgReceiver where Self: Send + Sync + 'static {
    fn send_start (&self,msg: _Start_, to: Duration) -> ObjSafeFuture<Result<()>>;
    fn send_pause (&self, msg: _Pause_, to: Duration) -> ObjSafeFuture<Result<()>>;
    fn send_resume (&self, msg: _Resume_, to: Duration) -> ObjSafeFuture<Result<()>>;
    fn send_terminate (&self, msg: _Terminate_, to: Duration) -> ObjSafeFuture<Result<()>>;

    // the whole purpose of ping is to measure response time - if we can't even send the Ping that's obviously exceeded
    fn send_ping (&self, msg: _Ping_) -> Result<()>;

    // timer events are not very useful if they can't be processed close to when they get emitted - don't clog the queue
    fn send_timer (&self, msg: _Timer_) -> Result<()>;
}

pub trait DefaultReceiveAction {
    fn default_receive_action (&self)->ReceiveAction;
}

/* #endregion runtime/channel agnostic sytem messages */

// a message set that only contains our system messages
define_actor_msg_type! {
    pub SysMsg = // only the automatically added system message variants
}

