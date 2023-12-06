#![allow(unused)]
// #![cfg(feature="tokio_channel")]

use tokio::{
    time::{self,Interval,interval},
    task::{self, JoinSet, LocalSet},
    runtime::Handle,
    sync::{mpsc::{self,error::TrySendError},oneshot},
};

use std::{
    time::{Instant,Duration},
    sync::{Arc,Mutex,atomic::AtomicU64},
    future::{Future, Ready},
    fmt::Debug, 
    pin::Pin,
    boxed::Box, 
    cell::Cell,
    marker::Sync,
    result::{Result as StdResult}
};
use crate::{
    Identifiable, ObjSafeFuture, SendableFutureCreator, ActorSystemRequest, create_sfc,
    errors::{Result,OdinActorError, all_op_result, poisoned_lock},
    MsgReceiver,DynMsgReceiver,ReceiveAction, DefaultReceiveAction,
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

pub type OneshotSender<M> = oneshot::Sender<M>;
pub type OneshotReceiver<M> = oneshot::Receiver<M>;
pub type MpscSender<M> = mpsc::Sender<M>;
pub type MpscReceiver<M> = mpsc::Receiver<M>;
pub type AbortHandle = task::AbortHandle;
pub type JoinHandle<T> = task::JoinHandle<T>;

#[inline]
fn create_mpsc_sender_receiver <MsgType> (bound: usize) -> (MpscSender<MsgType>,MpscReceiver<MsgType>)
    where MsgType: Send
{
    mpsc::channel(bound)
}

#[inline]
fn create_oneshot_sender_receiver <MsgType> () -> (OneshotSender<MsgType>,OneshotReceiver<MsgType>)
    where MsgType: Send
{
    oneshot::channel()
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

/// the trait that defines the message handler of an actor
pub trait Actor <MsgType> 
    where MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug
{
    // this is what makes an object an actor
    fn receive (&mut self, msg: MsgType, hself: &ActorHandle<MsgType>, hsys: &ActorSystemHandle) -> impl Future<Output = ReceiveAction> + Send;
}

// partly opaque struct
#[derive(Debug)]
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
        self.tx.try_send(msg).map_err(|e| {
            match e {
                TrySendError::Full(_) => OdinActorError::ReceiverFull,
                TrySendError::Closed(_) => OdinActorError::ReceiverClosed
            }
        })
    }

    pub fn try_send_msg<M: Into<MsgType>> (&self, msg:M)->Result<()> {
        self.try_send_actor_msg(msg.into())
    }

    // TODO - is this right to skip if we can't send? Maybe that should be an option

    pub fn start_oneshot_timer (&self, id: i64, delay: Duration) -> AbortHandle {
        let h = self.clone();

        let th = spawn( async move {
            sleep(delay).await;
            h.try_send_actor_msg( _Timer_{id}.into() );
        });
        th.abort_handle()
    }

    pub fn start_repeat_timer (&self, id: i64, timer_interval: Duration) -> AbortHandle {
        let h = self.clone();
        let mut interval = interval(timer_interval);

        let th = spawn( async move {
            while h.is_running() {
                interval.tick().await;
                if h.is_running() {
                    h.try_send_actor_msg( _Timer_{id}.into() );
                }
            }
        });
        th.abort_handle()
    }
}

impl <MsgType> Identifiable for ActorHandle<MsgType> 
    where MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static
{
    fn id (&self) -> &str { self.id.as_str() }
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

    pub async fn actor_of <MsgType,ActorType,T> (&self, actor: ActorType, bound: usize, id: T) -> Result<ActorHandle<MsgType>>
        where 
            MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static,
            ActorType: Actor<MsgType> + Clone + Send + 'static,
            T: ToString
    {
        let actor_id = Arc::new(id.to_string());
        let (tx, rx) = create_mpsc_sender_receiver::<MsgType>( bound);
        let actor_handle = ActorHandle { id: actor_id.clone(), tx };
        let hself = actor_handle.clone();
        let hsys = self.clone();

        let type_name = std::any::type_name::<ActorType>();
        let sys_msg_receiver = Box::new(actor_handle.clone());

        let func = move || { ActorSystem::run_actor( actor,hself,rx,hsys) };
        let sfc = create_sfc(func);

        self.send_msg( ActorSystemRequest::RequestActorOf { id: actor_id.clone(), type_name, sys_msg_receiver, sfc}, secs(1)).await?;

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

    // the public function to create an actor handle for a given actor object and spawn a task for it
    // the actor object is consumed so that it is no longer accessible from the caller context (the basic actor promise)
    // all type parameters should be inferred from the function arguments
    pub fn actor_of <MsgType,ActorType,T> (&mut self, actor: ActorType, bound: usize, id: T) -> ActorHandle<MsgType>
        where 
            MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static,
            ActorType: Actor<MsgType> + Send + 'static,
            T: ToString
    {
        let actor_id = Arc::new(id.to_string());

        let (tx, rx) = create_mpsc_sender_receiver::<MsgType>( bound);
        let actor_handle = ActorHandle { id: actor_id.clone(), tx };
        let hself = actor_handle.clone();
        let hsys = ActorSystemHandle { sender: self.request_sender.clone()};

        let abort_handle = self.join_set.spawn( Self::run_actor( actor,hself,rx,hsys) );

        let actor_entry = ActorEntry {
            id: actor_id,
            type_name: std::any::type_name::<ActorType>(),
            abortable: abort_handle,
            receiver: Box::new(actor_handle.clone()), // stores it as a SysMsgReceiver trait object
            ping_response: Arc::new(AtomicU64::new(0))
        };

        self.actor_entries.push( actor_entry);
        actor_handle
    }

    // the (internal) task function - consume the actor and loop while there are potential senders
    async fn run_actor <MsgType,ActorType> (
        mut actor: ActorType, 
        hself: ActorHandle<MsgType>, 
        mut rx: MpscReceiver<MsgType>, 
        hsys: ActorSystemHandle
    )
        where 
            MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static,
            ActorType: Actor<MsgType> + Send + 'static,
    {
        loop {
            match rx.recv().await {
                Some(msg) => {
                    match actor.receive(msg,&hself,&hsys).await {
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
                None => break // TODO shall we treat ReceiveError::Closed and ::SendClosed the same? what if there are no senders yet?
            }
        }

        // TODO - remove actor entry from ActorSystemData
    }

    // this is used from spawned actors sending us RequestActorOf messages
    fn spawn_actor (&mut self, actor_id: Arc<String>, type_name: &'static str, sys_msg_receiver: Box<dyn SysMsgReceiver>, sfc: SendableFutureCreator) {
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
                Some(msg) => {
                    use ActorSystemRequest::*;
                    match msg {
                        RequestTermination => {
                            self.terminate_and_wait(secs(5)).await?;
                            break;
                        }
                        RequestActorOf { id, type_name, sys_msg_receiver, sfc } => {
                            self.spawn_actor( id, type_name, sys_msg_receiver, sfc)
                        }
                    }
                }
                None => {
                    return Err(OdinActorError::ReceiverClosed) // ??
                }
            }
        }

        Ok(())
    }

}

/* #endregion ActorSystem */

/* #region message patterns *******************************************************************************/
/*
 * these functions are more complex and can use all our abstractions, but their interfaces are not
 * allowed to expose the concrete runtime/channel constructs since these functions are callable from
 * user (actor) code
 */

 /// trait to be implemented for all message types that can be used as questions (with expected answer of type A)
/// in synchronous ask() patterns.
pub trait Respondable<A> where A: Send + 'static, Self: Sized + Send {
    fn sender(self)->OneshotSender<A>;

    fn reply (self, answer: A)->impl std::future::Future<Output = Result<()>> + Send {
        let tx = self.sender();
        async move { tx.send(answer).map_err(|_| OdinActorError::ReceiverClosed) }
    }

    fn timeout_reply (self, to: Duration, answer: A)->impl std::future::Future<Output = Result<()>> + Send {
        let tx = self.sender();
        async move { tx.send(answer).map_err(|_| OdinActorError::ReceiverClosed) }
    }
}

pub async fn ask <Q,A,C> (tgt: impl MsgReceiver<Q>, ctor: C)->Result<A>
    where
        Q: Respondable<A> + Send + 'static, 
        A: Send + 'static,
        C: FnOnce(OneshotSender<A>)->Q
{
    let (tx,rx) = create_oneshot_sender_receiver::<A>();
    let q = ctor(tx);

    tgt.send_msg(q).await?;

    rx.await.map_err(|_| OdinActorError::SendersDropped)
}

/// create a question message of type Q, send it to tgt and timeout wait for an answer of type A.
/// this is the general pattern for synchronous message exchange 
pub async fn timeout_ask <Q,A,C> (to: Duration, tgt: impl MsgReceiver<Q>, ctor: C)->Result<A>
    where
        Q: Respondable<A> + Send + 'static, 
        A: Send + 'static,
        C: FnOnce(OneshotSender<A>)->Q
{
    let (tx,rx) = create_oneshot_sender_receiver::<A>();
    let q = ctor(tx);

    tgt.timeout_send_msg(q, to).await?;

    timeout(to, rx).await
}

// a sync try_ask() does not make sense since we do have to await the response 


/* #endregion message patterns */
