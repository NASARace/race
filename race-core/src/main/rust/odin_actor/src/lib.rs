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
#![allow(unused_imports)]
#![feature(trait_alias)]

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

#[cfg(feature = "tokio_kanal")]
pub mod tokio_kanal;
#[cfg(feature = "tokio_kanal")]
pub use tokio_kanal::{
    ActorSystem,ActorSystemHandle,Actor,ActorHandle,PreActorHandle,JoinHandle,AbortHandle,Query,QueryBuilder,
    sleep, timeout, yield_now, spawn, spawn_blocking, block_on, block_on_send_msg, block_on_timeout_send_msg,
    query, query_ref, timeout_query, timeout_query_ref,
};

#[cfg(feature = "tokio_channel")]
pub mod tokio_channel;

pub mod errors;
pub use errors::{OdinActorError,Result};

mod msg_patterns;
pub use msg_patterns::*;

extern crate odin_macro;
#[doc(hidden)]
pub use odin_macro::{
    define_actor_msg_type, match_actor_msg, cont, stop, term, impl_actor, 
    spawn_actor, spawn_dyn_actor, spawn_pre_actor, 
    define_actor_msg_action_type, define_actor_action_type, define_actor_action2_type
};


#[inline] pub fn days (n: u64)->Duration { Duration::from_secs(n*60*60*24) }
#[inline] pub fn hours (n: u64)->Duration { Duration::from_secs(n*60*60) }
#[inline] pub fn minutes (n: u64)->Duration { Duration::from_secs(n*60) }
#[inline] pub fn secs (n: u64)->Duration { Duration::from_secs(n) }
#[inline] pub fn millis (n: u64)->Duration { Duration::from_millis(n) }
#[inline] pub fn micros (n: u64)->Duration { Duration::from_micros(n) }
#[inline] pub fn nanos (n: u64)->Duration { Duration::from_nanos(n)} 


/// type that can be used for returning futures in object-safe (async) traits
pub type ObjSafeFuture<'a, T> = Pin<Box<dyn Future<Output=T> + Send + 'a>>;
pub type MsgSendFuture<'a> = ObjSafeFuture<'a,Result<()>>;

/// sendable function that returns a future
pub type SendableFutureCreator = Box<dyn FnOnce() -> Pin<Box<dyn Future<Output = ()> + Send>> + Send + Sync + 'static>;

pub trait MsgTypeConstraints = FromSysMsg + DefaultReceiveAction + Send + Debug + 'static;

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
    fn hsys (&self)->&ActorSystemHandle;
}

pub enum ReceiveAction {
    Continue, // continue receiving messages
    Stop,  // stop receiving messages
    RequestTermination, // ask actor system to send _Terminate_ messages
}

pub trait MsgReceiverConstraints = Identifiable + Debug + Send;

/// single message type receiver trait to abstract concrete ActorHandle<MsgSet> instances that would
/// force the client to know all messages the receiver understands, which reduces re-usability of the
/// receiver user. Note this trait is not object-safe (use [`DynMsgReceiver`] for dynamic subscription).
/// 
/// If we could turn the `impl Future...` returns into a concrete type (say MsgSendFuture) we could
/// avoid having a separate (and less efficient) `DynMsgReceiver`. Two of the channel crates (flume
/// and kanal) actually use SendFuture<'_,T> structs but we can't use them since hiding their message
/// type parameter is the whole point of MsgReceiver (which is only parametric in the actor message variant type) 
pub trait MsgReceiver<T>: TryMsgReceiver<T> + MsgReceiverConstraints + Clone {
    fn send_msg (&self, msg: T) -> impl Future<Output = Result<()>> + Send;
    fn timeout_send_msg (&self, msg: T, to: Duration) -> impl Future<Output = Result<()>> + Send;
}

/// this is a single message type receiver trait that is object safe, which means its
/// async [`send_msg`] and [`timeout_send_msg`] methods return [`ObjSafeFuture`] futures
/// (`Pin<Box<dyn Future<..>>>`), hence they incur runtime cost.
/// Since this trait needs to be object safe we cannot add Clone to its super-traits (which would imply Sized).
/// This trait is used to store abstract ActorHandles in places that cannot be parameterized
/// with concrete receiver types (e.g. if we need to store collections of potentially heterogenous receivers)
/// TODO.- explore if we can reduce runtime cost by means of specialized allocators (e.g. one that is actor
/// specific, i.e. only has to deal with one allocation at a time)
pub trait DynMsgReceiver<T>: TryMsgReceiver<T> + MsgReceiverConstraints {
    fn send_msg (&self, msg: T) -> MsgSendFuture;
    fn timeout_send_msg (&self, msg: T, to: Duration) -> MsgSendFuture;
}

/// a MsgReceiver that only supports non-async send. This trait is object safe and does not require
/// runtime overhead during execution (other than dynamic dispatch).
/// Since this trait needs to be object safe we cannot add Clone to its super-traits (which would imply Sized).
/// Note that it is up to the user to handle backpressure (upon OdinError::ReceiverFull)
pub trait TryMsgReceiver<T>: MsgReceiverConstraints {
    fn try_send_msg (&self, msg: T) -> Result<()>;
}

/// a list of ActorHandles implementing MsgReceiver<T> that we async send the same message to.
/// Use this if the list owner is in control of what message to send.
/// To create an ActorMsgList use the define_actor_msg_list!() macro
pub trait ActorSendMsgAction<T>: Send where T: Clone + Debug + Send {
    fn execute (&self,m:T) -> impl Future<Output=Result<()>> + Send;
} 

/// a list of ActorHandles with associated expressions we execute with list owner provided data. Conceptually
/// this is like a list of AsyncFn(ActorHandle,&D) if there would be such a thing.
/// Use this if the Actor call site (e.g. main()) is in control of actions.
/// To create an ActorActionList use the define_actor_action_list!() macro
pub trait ActorAction<D>: Send {
    fn execute (&self, data: &D) -> impl Future<Output=Result<()>> + Send;
}

/// an action list that is executed with two arguments. One of them is typically is a reference to own data, the
/// other one to external data received through the activation trigger. This is useful to implement async callbacks
/// that have to carry over information from the request.
/// While this could also be implemented with an ActorActionList that takes a tuple as argument type this would force us
/// to add lifetime parameters to the ActorActionList in case we want to pass in values as references, which
/// is the normal case for non-trivial owned data (which to maintain is the main reason for having the owner in the first place)
pub trait ActorAction2<A,B>: Send {
    fn execute (&self, a: &A,b: &B) -> impl Future<Output=Result<()>> + Send;
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

pub struct _Exec_(pub Box<dyn Fn() + Send + 'static>); // side effect executed from within actor task

impl Debug for _Exec_ {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "_Exec_(dyn FnOnce)")
    }
}

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
pub trait FromSysMsg: From<_Start_> + From<_Ping_> + From<_Timer_> + From<_Exec_> + From<_Pause_> + From<_Resume_> + From<_Terminate_> {}  

/// object-safe trait for each actor handle to send system messages
// TODO - should sent_timer() be async too?
pub trait SysMsgReceiver where Self: Send + Sync + 'static {
    fn send_start (&self,msg: _Start_, to: Duration) -> MsgSendFuture;
    fn send_pause (&self, msg: _Pause_, to: Duration) -> MsgSendFuture;
    fn send_resume (&self, msg: _Resume_, to: Duration) -> MsgSendFuture;
    fn send_terminate (&self, msg: _Terminate_, to: Duration) -> MsgSendFuture;

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
    pub SysMsg // only the automatically added system message variants
}
