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

use std::{future::{Future,Ready,ready}, fmt::Debug, time::Duration, pin::Pin, ops::Fn};
use crate::{DynMsgReceiver, MsgReceiver,errors::{Result, OdinActorError}};


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

/* #region callbacks ***********************************************************************************/

pub enum Callback<T> {
    Sync(SyncCallback<T>),
    Async(AsyncCallback<T>),
}

impl<T> Callback<T> {
    pub async fn execute (&self, v: &T)->Result<()> {
        match self {
            Callback::Sync(cb) => (cb.action)(v),
            Callback::Async(cb) => (cb.action)(v).await,
        }
    }
}

impl <T> From<AsyncCallback<T>> for Callback<T> {
    fn from (cb: AsyncCallback<T>)->Self { Callback::Async(cb) }
}
impl <T> From<SyncCallback<T>> for Callback<T> {
    fn from (cb: SyncCallback<T>)->Self { Callback::Sync(cb) }
}

impl<T> Debug for Callback<T> {
    fn fmt (&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Callback::Sync(cb) => write!(f, "SyncCallback<{}>(..)", std::any::type_name::<T>()),
            Callback::Async(cb) => write!(f, "AsyncCallback<{}>(..)", std::any::type_name::<T>())
        }
    }
}

//--- async callbacks

pub type AsyncCallbackFuture = Pin<Box<dyn Future<Output = Result<()>> + Send>>;
pub trait AsyncCallbackFn<T> = (Fn(T)->AsyncCallbackFuture) + Send + Sync;

pub struct AsyncCallback<T> {
    pub action: Box<dyn for<'a> AsyncCallbackFn<&'a T>>
}

impl <T> AsyncCallback<T> {
    pub async fn execute (&self, data: &T)->Result<()> {
        (&self.action)(data).await
    }
}

#[macro_export]
macro_rules! async_action {
    ($b: block) => { Box::pin( async move $b ) };
    ($e: expr) => { Box::pin( async move { $e }) };
}

#[macro_export]
macro_rules! async_callback {
    ( |$v:ident $(: $t:ty)?| $b:block ) => {
        AsyncCallback { action: Box::new( move |$v $(: $t )?| $b ) }
    };
    ( |$v:ident $(: $t:ty)?| $e:expr ) => {
        AsyncCallback { action: Box::new( move |$v $(: $t )?| async_action!( $e ) ) }
    };
}

#[macro_export]
macro_rules! send_msg_callback {
    ( $rcv:ident, |$v:ident $(: $t:ty)?| $e:expr ) => {
        {
            let rcv = $rcv.clone();
            AsyncCallback{ action: 
                Box::new( move |$v $(: $t)?| {
                    let msg = $e; 
                    let rcv = rcv.clone();
                    async_action!( rcv.send_msg( msg).await)
                })
            }
        }
    }
}

//--- sync callbacks (try_send_msg, logging etc.)

pub trait SyncCallbackFn<T> = (Fn(T)->Result<()>) + Send + Sync;

pub struct SyncCallback<T>{
    pub action: Box<dyn for<'a> SyncCallbackFn<&'a T>>
}

#[macro_export]
macro_rules! sync_callback {
    ( |$v:ident $(: $t:ty)?| $b:block ) => {
        SyncCallback::new( Box::new( move |$v $(: $t )?| $b ))
    };
    ( |$v:ident $(: $t:ty)?| $e:expr ) => {
        SyncCallback::new( Box::new( move |$v $(: $t )?| { $e } ))
    };
}

#[macro_export]
macro_rules! try_send_msg_callback {
    ( $rcv:ident, |$v:ident $(: $t:ty)?| $e:expr) => {
        {
            let rcv = $rcv.clone();
            SyncCallback{ action: 
                Box::new( move |$v $(: $t)?| {
                    let msg = $e; 
                    rcv.try_send_msg( msg)
                })
            }
        }
    }
}

//--- callback list

/// the field to store actions in. Note that we need to store trait objects here so that the owner does not
/// have to know action specifics, only its own associated input data type T 
pub struct CallbackList<T> { 
    entries: Vec<Callback<T>> 
}

impl <T> CallbackList<T> {
    pub fn new()->Self { 
        CallbackList{ entries: Vec::new() } 
    }

    pub fn is_empty (&self)->bool {
        self.entries.is_empty()
    }
    
    pub fn push (&mut self, cb: Callback<T>) { 
        self.entries.push( cb)
    }

    pub async fn execute (&self, v: &T)->Result<()> {
        let mut failed = 0;

        for cb in &self.entries {
            let res: Result<()> = match cb {
                Callback::Async(cb) => (cb.action)(v).await,
                Callback::Sync(cb) => (cb.action)(v),
                //Callback::TryMsgSend(cb) => cb.execute(v),
            };

            if res.is_err() { failed += 1 }
        }

        if failed > 0 {
            Err( OdinActorError::IterOpFailed { op: "callback execution".to_string(), all: self.entries.len(), failed })
        } else {
            Ok(())
        }
    }
}

/* #endregion callbacks */
