/*
 * Copyright (c) 2024, United States Government, as represented by the
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

#![allow(unused)]

 use std::{future::Future,fmt::Debug,time::Duration, pin::Pin, ops::Fn};
 use crate::{DynMsgReceiver, MsgReceiver,errors::{Result, OdinActorError}};

/// common interface between Query and OneshotQuery
pub trait Respondable<R> where R: Debug + Send {
    fn respond(self, r:R)->impl Future<Output=Result<()>>;
}

/* #region MsgSubscriber *******************************************************************************/

/// MsgSubscriber is the pattern to use if the publisher is defining the message to send out. While
/// this hides the type of the receiver it still requires all the receivers to be partly homogenous
/// (processing the same message). 
/// This is a trait object safe subscriber for receiving messages of type `M`, which
/// are created by the actor we subscribe to. While this has a low runtime overhead
/// it requires the subscribers to be homogenous (all receiving the same message type `M`),
/// i.e. it exposes subscriber details to the actor we subscribe to and hence reduces
/// re-usability. Use the more abstract `ActionSubscriptions` if this is not suitable
pub type MsgSubscriber<M> = Box<dyn DynMsgReceiver<M> + Send + Sync + 'static>;

pub fn msg_subscriber<M> (s: impl DynMsgReceiver<M> + Send + Sync + 'static)->MsgSubscriber<M> {
    Box::new(s)
}

/// container to keep a dynamically updated list of homogenous DynMsgReceiver instances.
/// MsgSubscriptions objects are used as fields within the actor we subscribe to, to implement a
/// publish/subscribe pattern that hides the concrete types of the subscribers (which don't even have to be actors) 
pub struct MsgSubscriptions<M>
    where M: Send + Clone + Debug + 'static
{
    list: Vec<MsgSubscriber<M>>, 
}

// TODO - should we automatically remove subscribers we fail to send to?
impl<M> MsgSubscriptions<M> 
    where M: Send + Clone + Debug + 'static
{
    pub fn new()->Self {
        MsgSubscriptions { list: Vec::new() }
    }

    pub fn add (&mut self, subscriber: MsgSubscriber<M>) {
        self.list.push( subscriber);
    }

    pub async fn publish_msg (&self, msg: M) -> Result<()> {
        for p in &self.list {
            p.send_msg( msg.clone()).await?;
        }
        Ok(())
    }

    pub async fn timeout_publish_msg (&self, msg: M, to: Duration) -> Result<()> {
        for ref p in &self.list {
            p.timeout_send_msg( msg.clone(), to).await?;
        }
        Ok(())
    }
}

/* #endregion MsgSubscriber */

/* #region callback *********************************************************************/

#[derive(Debug)]
pub enum Callback<T> where T: Clone + Send + Sync {
    Sync(SyncCallback<T>),
    Async(AsyncCallback<T>)
}

impl <T> Callback<T> where T: Clone + Send + Sync {
    pub async fn trigger (&self, data: T)->Result<()> {
        match self {
            Callback::Sync(cb) => (cb.action)(data),
            Callback::Async(cb) => (cb.action)(data).await
        }
    }
}

//--- Async

pub type CallbackFuture = Pin<Box<dyn Future<Output = Result<()>> + Send>>;
pub type AsyncCallbackFn<T> = dyn (Fn(T)->CallbackFuture) + Send + Sync;

pub struct AsyncCallback<T> where T: Clone + Send + Sync {
    action: Box<AsyncCallbackFn<T>>
}
impl <T> AsyncCallback<T> where T: Clone + Send + Sync {
    pub fn new<F> (action: F)->Self where F: (Fn(T)->CallbackFuture) + Send + Sync + 'static {
        AsyncCallback { action: Box::new(action) }
    }
}

/// this is a specialized async callback with an action that sends a message back to
/// some (possibly 3rd) actor. The Fn(T)->M argument returns the message that is sent 
/// ```
///   let htracks = spawn_actor!(...)
///   let hserver = spawn_actor!(...);
///   ...
///   htracks.send_msg( Execute( msg_callback!( hserver, |tracks: Vec<Track>| TrackList(tracks)).await?;
/// ```
#[macro_export]
macro_rules! msg_callback {
    { $tgt:expr, | $d:ident : $t_d:ty | $e:expr } =>  {
        {
            let recipient = $tgt.clone();
            Callback::Async( AsyncCallback::new (
                move | $d : $t_d | {
                    let recipient = recipient.clone(); // this is called multiple times
                    Box::pin( recipient.move_send_msg( $e) )
                }
            ))
        }
    };
    { $tgt:expr, $e:expr } => {
        {
            let recipient = $tgt.clone();
            Callback::Async( AsyncCallback::new (
                move |()| {
                    let recipient = recipient.clone(); // this is called multiple times
                    Box::pin( recipient.move_send_msg( $e) )
                }
            ))
        }
    };
}

#[macro_export]
macro_rules! async_callback {
    { |$v:ident : $t:ty| $e:expr } => { 
        Callback::Async( AsyncCallback::new( |$v : $t| Box::pin( async move { $e.await } )) ) 
    };

    { || $e:expr } => {
        Callback::Async( AsyncCallback::new( |()| Box::pin( async move { $e.await } )) ) 
    }
}


// we need a Debug impl so that we can have messages that have Callback objects as payloads
impl<T> Debug for AsyncCallback<T> where T: Clone + Send + Sync {
    fn fmt (&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "AsyncCallback<{}>(..)", std::any::type_name::<T>())
    }
}

//--- Sync

pub type SyncCallbackFn<T> = dyn (Fn(T)->Result<()>) + Send + Sync;

pub struct SyncCallback<T> where T: Clone + Send {
    action: Box<SyncCallbackFn<T>>
}
impl <T> SyncCallback<T> where T: Clone + Send {
    pub fn new<F> (action: F)->Self where F: (Fn(T) -> Result<()>) + Send + Sync + 'static {
        SyncCallback { action: Box::new(action) }
    }
}

impl<T> Debug for SyncCallback<T> where T: Clone + Send + Sync {
    fn fmt (&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "SyncCallback<{}>(..)", std::any::type_name::<T>())
    }
}

#[macro_export]
macro_rules! sync_callback {
    ( |$v:ident : $t:ty| $blk:block ) =>
    { Callback::Sync(SyncCallback::new( |$v : $t| $blk)) }
}


pub struct CallbackList<T> where T: Clone + Send + Sync {
    list: Vec<(String,Callback<T>)>
}

impl <T> CallbackList<T> where T: Clone + Send + Sync {
    pub fn new()->Self {
        CallbackList { list: Vec::new() }
    }

    pub fn is_empty (&self)->bool {
        self.list.is_empty()
    }

    pub fn add (&mut self, id: String, cb: Callback<T>) {
        self.list.push( (id,cb));
    }

    pub async fn trigger (&self, data: T)->Result<()> {
        let mut failed = 0;

        for (_,cb) in &self.list {
            match cb.trigger( data.clone()).await {
                Ok(()) => {}
                Err(e) => failed += 1
            }
        }

        if failed > 0 {
            Err( OdinActorError::AllOpFailed { op: "trigger callbacks".to_string(), all: self.list.len(), failed })
        } else {
            Ok(())
        }
    }
}

/* #endregion callback */