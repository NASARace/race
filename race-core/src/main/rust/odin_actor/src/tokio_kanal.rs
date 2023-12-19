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
#![allow(unused)]
// #![cfg(feature="tokio_channel")]

use tokio::{
    time::{self,Interval,interval},
    task::{self, JoinSet, LocalSet},
    runtime::Handle
};
use kanal::{
    bounded_async,AsyncSender,AsyncReceiver, SendError
};
use std::{
    any::type_name,
    time::{Instant,Duration},
    sync::{Arc,Mutex,atomic::AtomicU64},
    future::Future,
    fmt::Debug, 
    pin::Pin,
    boxed::Box, 
    cell::Cell,
    marker::{Sync, PhantomData},
    result::{Result as StdResult},
    ops::{Deref,DerefMut}
};
use crate::{
    Identifiable, ObjSafeFuture, SendableFutureCreator, ActorSystemRequest, create_sfc,
    errors::{Result,OdinActorError, all_op_result, poisoned_lock},
    ActorReceiver,MsgReceiver,DynMsgReceiver,ReceiveAction, DefaultReceiveAction, Respondable,
    secs,millis,micros,nanos,
    SysMsgReceiver, FromSysMsg, _Start_, _Ping_, _Timer_, _Pause_, _Resume_, _Terminate_,
};

/* #region runtime abstractions ***************************************************************************/
/*
 * This section is (mostly) for type and function aliases that allow us to program our own structs/traits/impls
 * without having to explicitly use runtime or channel crate specifics. While this means we still have
 * runtime/channel specific Actors, ActorHandles etc. their source code is (mostly) similar. 
 * Trying to hoist our actor constructs to crate level would require generic types that make code less readable
 * and still result in more runtime overhead (boxing/unboxing trait objects etc.). Moreover, it is not even
 * desirable to hoist some constructs since they are not compatible between runtime/channel implementations.
 */

pub type MpscSender<M> = AsyncSender<M>;
pub type MpscReceiver<M> =AsyncReceiver<M>;
pub type AbortHandle = task::AbortHandle;
pub type JoinHandle<T> = task::JoinHandle<T>;

#[inline]
fn create_mpsc_sender_receiver <MsgType> (bound: usize) -> (MpscSender<MsgType>,MpscReceiver<MsgType>)
    where MsgType: Send
{
    kanal::bounded_async::<MsgType>(bound)
}

#[inline]
pub async fn sleep (dur: Duration) {
    time::sleep(dur).await;
}

#[inline]
pub async fn timeout<F,R,E> (to: Duration, fut: F)->Result<R> where F: Future<Output=StdResult<R,E>> {
    match time::timeout( to, fut).await {
        Ok(result) => result.map_err(|_| OdinActorError::SendersDropped),
        Err(e) => Err(OdinActorError::TimeoutError(to))
    }
}

#[inline]
pub async fn yield_now () {
    task::yield_now().await;
}

#[inline]
pub fn spawn<F>(future: F) -> JoinHandle<F::Output>
    where
        F: Future + Send + 'static,
        F::Output: Send + 'static,
{
    task::spawn( future)
}

#[inline]
pub fn spawn_blocking<F,R> (fn_once: F) -> JoinHandle<F::Output>
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static
{
    task::spawn_blocking( fn_once)
}

// these functions can be used to communicate back to the actor once the spawn_blocking() executed FnOnce is done

pub fn block_on<F: Future>(future: F) -> F::Output {
    Handle::current().block_on( future)
}

/// a specialized version that uses a try_send_msg() from within a blocking loop.
/// Note this comes with the additional cost/constraint of a Clone constraint for the message
pub fn block_on_send_msg<Msg> (tgt: impl MsgReceiver<Msg>, msg: Msg)->Result<()> where Msg: Send + Clone {
    loop {
        match tgt.try_send_msg(msg.clone()) {
            Ok(()) => return Ok(()),
            Err(e) => match e {
                OdinActorError::ReceiverFull => std::thread::sleep(millis(30)),
                _ => return Err(e)
            }
        }
    }
}

/// a timeout version of a blocking try_send_msg() loop. Use this if it is not at the end of the spawn_blocking() task
pub fn block_on_timeout_send_msg<Msg> (tgt: impl MsgReceiver<Msg>, msg: Msg, to: Duration)->Result<()> where Msg: Send + Clone {
    let mut elapsed = millis(0);
    loop {
        match tgt.try_send_msg(msg.clone()) {
            Ok(()) => return Ok(()),
            Err(e) => match e {
                OdinActorError::ReceiverFull => {
                    if elapsed > to {
                        return Err(OdinActorError::TimeoutError(to))
                    }
                    let dt = millis(30);
                    std::thread::sleep(dt); // note this is just an approximation but we don't try to minimize latency here
                    elapsed += dt;
                }
                _ => return Err(e)
            }
        }
    }
}

/* #endregion runtime abstractions */

/* #region Actor and ActorHandle *******************************************************************************/
/*
 * We could hoist Actor and ActorHandle if we put MpscSender and Abortable behind traits and add them as
 * generic type params but that would obfuscate the code. The real optimization we would like is to avoid
 * MsgReceiver trait objects but those seem necessary for dynamic (msg based) subscription 
 */

pub struct Actor <StateType,MsgType> 
where 
    StateType: Send + 'static, 
    MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    pub state: StateType,
    pub hself: ActorHandle<MsgType>,
    pub hsys: ActorSystemHandle
}

impl <StateType,MsgType> Actor <StateType,MsgType> 
where 
    StateType: Send + 'static, 
    MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    //--- unfortunately we can only have one Deref so we forward these explicitly

    #[inline(always)]
    pub fn id (&self)->&str {
        self.hself.id()
    }

    #[inline(always)]
    pub fn send_msg<M:Into<MsgType>> (&self, msg: M)->impl Future<Output=Result<()>> {
        self.hself.send_actor_msg( msg.into())
    }

    #[inline(always)]
    pub fn timeout_send_msg<M:Into<MsgType>> (&self, msg: M, to: Duration)->impl Future<Output=Result<()>> {
        self.hself.timeout_send_actor_msg( msg.into(), to)
    }

    #[inline(always)]
    pub fn try_send_msg<M: Into<MsgType>> (&self, msg:M)->Result<()> {
        self.hself.try_send_actor_msg(msg.into())
    }

    #[inline(always)]
    pub fn start_oneshot_timer (&self, id: i64, delay: Duration) -> AbortHandle {
        oneshot_timer_for( self.hself.clone(), id, delay)
    }

    #[inline(always)]
    pub fn start_repeat_timer (&self, id: i64, timer_interval: Duration) -> AbortHandle {
        repeat_timer_for( self.hself.clone(), id, timer_interval)
    }

    #[inline(always)]
    pub async fn request_termination (&self, to: Duration)->Result<()> {
        self.hsys.send_msg( ActorSystemRequest::RequestTermination, to).await
    }
}


impl <StateType,MsgType> Deref for Actor<StateType,MsgType>
where 
    StateType: Send + 'static, 
    MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    type Target = StateType;

    fn deref(&self) -> &Self::Target {
        &self.state
    }
}

impl <StateType,MsgType> DerefMut for Actor<StateType,MsgType>
where 
    StateType: Send + 'static, 
    MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.state
    }
}


// partly opaque struct
pub struct ActorHandle <MsgType> 
    where MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    pub id: Arc<String>,
    tx: MpscSender<MsgType> // internal - this is channel specific
}

impl <MsgType> ActorHandle <MsgType> 
    where MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static,
{
    fn is_running(&self) -> bool {
        !self.tx.is_closed()
    }

    /// this waits indefinitely until the message can be send or the receiver got closed
    pub async fn send_actor_msg (&self, msg: MsgType)->Result<()> {
        self.tx.send(msg).await.map_err(|_| OdinActorError::ReceiverClosed)
    }

    pub async fn send_msg<M:Into<MsgType>> (&self, msg: M)->Result<()> {
        self.send_actor_msg( msg.into()).await
    }

    /// this waits for a given timeout duration until the message can be send or the receiver got closed
    pub async fn timeout_send_actor_msg (&self, msg: MsgType, to: Duration)->Result<()> {
        timeout( to, self.send_actor_msg(msg)).await
    }

    pub async fn timeout_send_msg<M:Into<MsgType>> (&self, msg: M, to: Duration)->Result<()> {
        self.timeout_send_actor_msg( msg.into(), to).await
    }

    /// this returns immediately but the caller has to check if the message got sent
    pub fn try_send_actor_msg (&self, msg: MsgType)->Result<()> {
        match self.tx.try_send(msg) {
            Ok(true) => Ok(()),
            Ok((false)) => Err(OdinActorError::ReceiverFull),
            Err(_) => Err(OdinActorError::ReceiverClosed), // ?? what about SendError::Closed 
        }
    }

    pub fn try_send_msg<M: Into<MsgType>> (&self, msg:M)->Result<()> {
        self.try_send_actor_msg(msg.into())
    }

    // TODO - is this right to skip if we can't send? Maybe that should be an option

    pub fn start_oneshot_timer (&self, id: i64, delay: Duration) -> AbortHandle {
        oneshot_timer_for( self.clone(), id, delay)
    }

    pub fn start_repeat_timer (&self, id: i64, timer_interval: Duration) -> AbortHandle {
        repeat_timer_for( self.clone(), id, timer_interval)
    }
}

// note this consumed the ActorHandle since we have to move it into a Future
fn oneshot_timer_for<MsgType> (ah: ActorHandle<MsgType>, id: i64, delay: Duration)->AbortHandle
where MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    let th = spawn( async move {
        sleep(delay).await;
        ah.try_send_actor_msg( _Timer_{id}.into() );
    });
    th.abort_handle()
}

fn repeat_timer_for<MsgType> (ah: ActorHandle<MsgType>, id: i64, timer_interval: Duration)->AbortHandle
where MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    let mut interval = interval(timer_interval);

    let th = spawn( async move {
        while ah.is_running() {
            interval.tick().await;
            if ah.is_running() {
                ah.try_send_actor_msg( _Timer_{id}.into() );
            }
        }
    });
    th.abort_handle()
}



impl <MsgType> Identifiable for ActorHandle<MsgType> 
where MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    fn id (&self) -> &str { self.id.as_str() }
}

impl <MsgType> Debug for ActorHandle<MsgType>
where MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "ActorHandle(\"{}\")", self.id)
    }
}

impl <MsgType> Clone for ActorHandle <MsgType>
    where MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    fn clone(&self)->Self {
        ActorHandle::<MsgType> { id: self.id.clone(), tx: self.tx.clone() }
    }
}

/// blanket impl of non-object-safe trait that can send anything that can be turned into a MsgType
/// (use [`DynMsgReceiver`] if this needs to be sent/stored as trait object)
impl <M,MsgType> MsgReceiver <M> for ActorHandle <MsgType>
    where 
        M: Send + Debug + 'static,
        MsgType: From<M> + FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    fn send_msg (&self, msg: M) -> impl Future<Output = Result<()>> + Send {
        self.send_actor_msg( msg.into())
    }

    fn timeout_send_msg (&self, msg: M, to: Duration) -> impl Future<Output = Result<()>> + Send {
        self.timeout_send_actor_msg( msg.into(), to)
    }

    fn try_send_msg (&self, msg: M) -> Result<()> {
        self.try_send_actor_msg( msg.into())
    }
}

/// blanket impl of object safe trait that can send anything that can be turned into a MsgType 
impl <M,MsgType> DynMsgReceiver <M> for ActorHandle <MsgType>
    where 
        M: Send + Debug + 'static,
        MsgType: From<M> + FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    fn send_msg (&self, msg: M) -> ObjSafeFuture<Result<()>> {
        Box::pin( self.send_actor_msg( msg.into()))
    }

    fn timeout_send_msg (&self, msg: M, to: Duration) -> ObjSafeFuture<Result<()>> {
        Box::pin( self.timeout_send_actor_msg( msg.into(), to))
    }

    fn try_send_msg (&self, msg: M) -> Result<()> {
        self.try_send_actor_msg( msg.into())
    }
}


impl <MsgType> SysMsgReceiver for ActorHandle<MsgType>
    where MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    fn send_start (&self,msg: _Start_, to: Duration)->ObjSafeFuture<Result<()>> {
        Box::pin(self.timeout_send_actor_msg(msg.into(),to)) 
    }
    fn send_pause (&self, msg: _Pause_, to: Duration)->ObjSafeFuture<Result<()>> {
        Box::pin(self.timeout_send_actor_msg(msg.into(),to)) 
    }
    fn send_resume (&self, msg: _Resume_, to: Duration)->ObjSafeFuture<Result<()>> {
        Box::pin(self.timeout_send_actor_msg(msg.into(),to)) 
    }
    fn send_terminate (&self, msg: _Terminate_, to: Duration)->ObjSafeFuture<Result<()>> {
        Box::pin(self.timeout_send_actor_msg(msg.into(),to)) 
    }
    fn send_ping (&self, msg: _Ping_)->Result<()> {
        self.try_send_actor_msg(msg.into()) 
    }
    fn send_timer (&self, msg: _Timer_)->Result<()> {
        self.try_send_actor_msg(msg.into()) 
    }
}


/* #endregion ActorHandle */

/* #region ActorSystem ************************************************************************************/

struct ActorEntry {
    id: Arc<String>,
    type_name: &'static str,
    abortable: AbortHandle,
    receiver: Box<dyn SysMsgReceiver>,
    ping_response: Arc<AtomicU64>
}

#[derive(Clone)]
pub struct ActorSystemHandle {
    sender: MpscSender<ActorSystemRequest>
}
impl ActorSystemHandle {
    pub async fn send_msg (&self, msg: ActorSystemRequest, to: Duration)->Result<()> {
        timeout( to, self.sender.send(msg)).await
    }

    pub fn new_actor<T,MsgType> (&self, id: impl ToString, state: T, bound: usize) 
            -> (Actor<T,MsgType>, ActorHandle<MsgType>, MpscReceiver<MsgType>)
    where 
        T: Send + 'static,
        MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
    {
        actor_tuple_for( self.clone(), id, state, bound)
    }

    pub async fn spawn_actor<MsgType,ReceiverType> (&self, act: (ReceiverType, ActorHandle<MsgType>, MpscReceiver<MsgType>))->Result<ActorHandle<MsgType>> 
    where
        MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static,
        ReceiverType: ActorReceiver<MsgType> + Send + Sync + 'static
    {
        let (mut receiver, actor_handle, rx) = act;
        let id = actor_handle.id.clone();
        let type_name = std::any::type_name::<ReceiverType>();
        let sys_msg_receiver = Box::new(actor_handle.clone());
        let hsys = self.clone();
        let func = move || { run_actor(rx, receiver, hsys) };
        let sfc = create_sfc( func);

        self.send_msg( ActorSystemRequest::RequestActorOf { id, type_name, sys_msg_receiver, sfc }, secs(1)).await?;
        Ok(actor_handle)
    }

    pub async fn request_termination (&self, to: Duration)->Result<()> {
        self.send_msg( ActorSystemRequest::RequestTermination, to).await
    }
}


/// the ActorSystem representation for the function in which it is created
pub struct ActorSystem {
    id: String,
    ping_cycle: u32,
    request_sender: MpscSender<ActorSystemRequest>,
    request_receiver: MpscReceiver<ActorSystemRequest>,
    join_set: task::JoinSet<()>, 
    actor_entries: Vec<ActorEntry>
}

impl ActorSystem {

    pub fn new<T: ToString> (id: T)->Self {
        let (tx,rx) = create_mpsc_sender_receiver(8);

        ActorSystem { 
            id: id.to_string(), 
            ping_cycle: 0,
            request_sender: tx,
            request_receiver: rx,
            join_set: JoinSet::new(),
            actor_entries: Vec::new()
        }
    }

    // these two functions need to be called at the user code level. The separation is required to guarantee that
    // there is a Receiver<MsgType> impl for the respective Actor<T,MsgType> - the new_(..) returns the concrete Actor<T,MsgType>
    // and the spawn_(..) expects a Receiver<MsgType> and hence fails if there is none in scope. The ugliness comes in form
    // of all the ActorSystem internal data we create in new_(..) but need in spawn_(..) and unfortunately we can't even use
    // the Actor hself field since spawn_(..) doesn't even see that it's an Actor (it consumes the Receiver).
    // We can't bypass Receiver by providing receive() through a fn()->impl Future<..> since impl-in-return-pos is not 
    // supported for fn pointers.
    // We also can't use a default blanket Receive impl for Actor and min_specialization - apart from that it isn't stable yet
    // it does not support async traits

    pub fn new_actor<StateType,MsgType> (&self, id: impl ToString, state: StateType, bound: usize) 
            -> (Actor<StateType,MsgType>, ActorHandle<MsgType>, MpscReceiver<MsgType>)
        where 
            StateType: Send + 'static,
            MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
    {
        actor_tuple_for( ActorSystemHandle { sender: self.request_sender.clone()}, id, state, bound)
    }

    /// although this implementation is infallible others (e.g. through an [`ActorHandle`] or using different
    /// channel types) are not. To keep it consistent we return a `Result<ActorHandle>``
    pub fn spawn_actor <MsgType,ReceiverType> (&mut self, act: (ReceiverType, ActorHandle<MsgType>, MpscReceiver<MsgType>))
            ->Result<ActorHandle<MsgType>>
        where
            MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static,
            ReceiverType: ActorReceiver<MsgType> + Send + 'static
    {
        let (mut receiver, actor_handle, rx) = act;
        let hsys = ActorSystemHandle { sender: self.request_sender.clone()};

        let abort_handle = self.join_set.spawn( run_actor(rx, receiver, hsys));

        let actor_entry = ActorEntry {
            id: actor_handle.id.clone(),
            type_name: type_name::<ReceiverType>(),
            abortable: abort_handle,
            receiver: Box::new(actor_handle.clone()), // stores it as a SysMsgReceiver trait object
            ping_response: Arc::new(AtomicU64::new(0))
        };
        self.actor_entries.push( actor_entry);

        Ok(actor_handle)
    }


    // this is used from spawned actors sending us RequestActorOf messages
    fn spawn_actor_request (&mut self, actor_id: Arc<String>, type_name: &'static str, sys_msg_receiver: Box<dyn SysMsgReceiver>, sfc: SendableFutureCreator) {
        let abort_handle = self.join_set.spawn( sfc());
        let actor_entry = ActorEntry {
            id: actor_id,
            type_name,
            abortable: abort_handle,
            receiver: sys_msg_receiver, // stores it as a SysMsgReceiver trait object
            ping_response: Arc::new(AtomicU64::new(0))
        };

        self.actor_entries.push( actor_entry);
    }


    // this should NOT be accessible from actors, hence we require a &mut self
    pub async fn wait_all (&mut self, to: Duration) -> Result<()> {
        let mut join_set = &mut self.join_set;

        let len = join_set.len();
        let mut closed = 0;
        while let Some(_res) = join_set.join_next().await {
            closed += 1;
        }
        
        all_op_result("start_all", len, len-closed)   
    }


    pub async fn abort_all (&mut self) {
        let mut join_set = &mut self.join_set;
        join_set.abort_all();
    }

    pub async fn ping_all (&mut self, to: Duration)->Result<()> {
        let actor_entries = &self.actor_entries;

        self.ping_cycle += 1;
        for actor_entry in actor_entries {
            let response = actor_entry.ping_response.clone();
            actor_entry.receiver.send_ping( _Ping_{ cycle: self.ping_cycle, sent: Instant::now(), response });
        }
        Ok(())
    }

    pub async fn start_all (&self, to: Duration)->Result<()> {
        let actor_entries = &self.actor_entries;

        let mut failed = 0;
        for actor_entry in actor_entries {
            if actor_entry.receiver.send_start(_Start_{}, to).await.is_err() { failed += 1 }
        }
        // TODO - do we need to wait until everybody has processed _Start_ ?
        all_op_result("start_all", actor_entries.len(), failed)
    }

    pub async fn terminate_all (&self, to: Duration)->Result<()>  {
        let mut len = 0;
        let mut failed = 0;

        //for actor_entry in self.actors.iter().rev() { // send terminations in reverse ?
        for actor_entry in self.actor_entries.iter() {
            len += 1;
            if actor_entry.receiver.send_terminate(_Terminate_{}, to).await.is_err() { failed += 1 };
        }

        // no need to wait for responses since we use the join_set to sync
        all_op_result("terminate_all", len, failed)
    }

    pub async fn terminate_and_wait (&mut self, to: Duration)->Result<()> {
        self.terminate_all( to).await;

        let res = self.wait_all(to).await;
        if (res.is_err()) {
            self.abort_all().await
        }
        res
    }

    pub async fn process_requests (&mut self)->Result<()> {
        loop {
            match self.request_receiver.recv().await {
                Ok(msg) => {
                    use ActorSystemRequest::*;
                    match msg {
                        RequestTermination => {
                            self.terminate_and_wait(secs(5)).await?;
                            break;
                        }
                        RequestActorOf { id, type_name, sys_msg_receiver, sfc } => {
                            self.spawn_actor_request( id, type_name, sys_msg_receiver, sfc)
                        }
                    }
                }
                Err(_) => {
                    return Err(OdinActorError::ReceiverClosed) // ??
                }
            }
        }

        Ok(())
    }

}

fn actor_tuple_for<StateType,MsgType> (hsys: ActorSystemHandle, id: impl ToString, state: StateType, bound: usize)
           -> (Actor<StateType,MsgType>, ActorHandle<MsgType>, MpscReceiver<MsgType>)
where 
    StateType: Send + 'static,
    MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    let actor_id = Arc::new(id.to_string());
    let (tx, rx) = create_mpsc_sender_receiver::<MsgType>( bound);
    let actor_handle = ActorHandle { id: actor_id, tx };
    let hself = actor_handle.clone();
    let actor = Actor{ state, hself, hsys };

    (actor, actor_handle, rx)
}

async fn run_actor <MsgType,ReceiverType> (mut rx: MpscReceiver<MsgType>, mut receiver: ReceiverType, hsys: ActorSystemHandle)
where
    MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static,
    ReceiverType: ActorReceiver<MsgType> + Send + 'static
{
    loop {
        match rx.recv().await {
            Ok(msg) => {
                match receiver.receive(msg).await {
                    ReceiveAction::Continue => {} // just go on
                    ReceiveAction::Stop => {
                        rx.close();
                        break;
                    }
                    ReceiveAction::RequestTermination => {
                        hsys.send_msg(ActorSystemRequest::RequestTermination, secs(1)).await;
                    }
                }
            }
            Err(_) => break // TODO shall we treat ReceiveError::Closed and ::SendClosed the same? what if there are no senders yet?
        }
    }

    // TODO - remove actor entry from ActorSystemData
}

/* #endregion ActorSystem */

/* #region Queries ***************************************************************************/

/// QueryBuilder avoids the extra cost of a per-request channel allocation and is therefore slightly faster
/// compared to a per-query Oneshot channel
pub struct QueryBuilder<R>  where R: Send + Debug {
    tx: MpscSender<R>,
    rx: MpscReceiver<R>,
}

impl <R> QueryBuilder<R> where R: Send + Debug {
    pub fn new ()->Self {
        let (tx,rx) = create_mpsc_sender_receiver::<R>(1);
        QueryBuilder { tx, rx }
    }

    pub async fn query <M,T> (&self, responder: M, topic: T)->Result<R> 
    where T: Send + Debug, M: MsgReceiver<Query<T,R>>
    {
        let msg = Query { topic, tx: self.tx.clone() };
        responder.send_msg(msg).await;
        self.rx.recv().await.map_err(|_| OdinActorError::SendersDropped)
    }

    /// if we use this version `M` has to be `Send` + `Sync` but we save the cost of cloning the responder on each query
    pub async fn query_ref <M,T> (&self, responder: &M, topic: T)->Result<R> 
    where T: Send + Debug, M: MsgReceiver<Query<T,R>> + Sync
    {
        let msg = Query { topic, tx: self.tx.clone() };
        responder.send_msg(msg).await;
        self.rx.recv().await.map_err(|_| OdinActorError::SendersDropped)
    }

    pub async fn timeout_query <M,T> (&self, responder: M, topic: T, to: Duration)->Result<R> 
    where T: Send + Debug, M: MsgReceiver<Query<T,R>>
    {
        timeout( to, self.query( responder, topic)).await
    }

    /// if we use this version `M` has to be `Send` + `Sync` but we save the cost of cloning the responder on each query
    pub async fn timeout_query_ref <M,T> (&self, responder: &M, topic: T, to: Duration)->Result<R> 
    where T: Send + Debug, M: MsgReceiver<Query<T,R>> + Sync
    {
        timeout( to, self.query_ref( responder, topic)).await
    }
}

pub struct Query<T,R> where T: Send + Debug, R: Send + Debug {
    pub topic: T,
    tx: MpscSender<R>
}

impl <T,R> Respondable<R> for Query<T,R> where T: Send + Debug, R: Send + Debug {
    async fn respond (self, r: R)->Result<()> {
        self.tx.send(r).await.map_err(|_| OdinActorError::ReceiverClosed)
    }
}

impl<T,R> Debug for Query<T,R>  where T: Send + Debug, R: Send + Debug {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Request<{},{}>{:?})", type_name::<T>(), type_name::<R>(), self.topic)
    }
}

/// oneshot query
pub async fn query<T,R,M> (responder: M, topic: T)->Result<R> 
where T: Send + Debug, R: Send + Debug, M: MsgReceiver<Query<T,R>>
{
    let qb = QueryBuilder::<R>::new();
    qb.query( responder, topic).await
}

pub async fn query_ref<T,R,M> (responder: &M, topic: T)->Result<R> 
where T: Send + Debug, R: Send + Debug, M: MsgReceiver<Query<T,R>> + Sync
{
    let qb = QueryBuilder::<R>::new();
    qb.query_ref( responder, topic).await
}

/// oneshot timeout query
pub async fn timeout_query<T,R,M> (responder: M, topic: T, to: Duration)->Result<R> 
where T: Send + Debug, R: Send + Debug, M: MsgReceiver<Query<T,R>>
{
    let qb = QueryBuilder::<R>::new();
    qb.timeout_query( responder, topic, to).await
}

pub async fn timeout_query_ref<T,R,M> (responder: &M, topic: T, to: Duration)->Result<R> 
where T: Send + Debug, R: Send + Debug, M: MsgReceiver<Query<T,R>> + Sync
{
    let qb = QueryBuilder::<R>::new();
    qb.timeout_query_ref( responder, topic, to).await
}

/* #endregion QueryBuilder & Query */

// we ditch the OneshotQuery (using a oneshot channel) since it doesn't really save us anything