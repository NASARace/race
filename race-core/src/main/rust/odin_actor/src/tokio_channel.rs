#![allow(unused)]
#![cfg(feature="tokio_channel")]

use tokio::{
    sync::{mpsc,oneshot},
    time::{self,Interval,timeout,interval},
    task
};
use std::{
    time::{Instant,Duration},
    sync::{Arc, atomic::AtomicU64},
    future::Future,
    fmt::Debug, 
    pin::Pin,
    boxed::Box, 
    cell::Cell,
    marker::Sync
};
use crate::{
    Identifiable, ObjSafeFuture,
    errors::{Result,OdinActorError, all_op_failed},
    MsgReceiver,DynMsgReceiver,ReceiveAction, DefaultReceiveAction,
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
    mpsc::channel::<MsgType>(bound)
}

#[inline]
fn create_oneshot_sender_receiver <MsgType> () -> (OneshotSender<MsgType>,OneshotReceiver<MsgType>)
    where MsgType: Send
{
    oneshot::channel::<MsgType>()
}

#[inline]
pub async fn sleep (dur: Duration) {
    time::sleep(dur).await;
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

/// trait to be implemented for all message types that can be used as questions (with expected answer of type A)
/// in synchronous ask() patterns.
/// The create_question() constructor is called from within our generic ask(..) functions, which are creating
/// the respective OneshotSender/Receiver pairs
pub trait Respondable<A> 
    where
        A: Send + 'static, 
        Self: Sized + Send
{
    fn create_question (tx: OneshotSender<A>) -> Self;
}

#[derive(Debug)]
pub struct Abortable {
    abort_handle: tokio::task::AbortHandle
}
impl Abortable {
    pub fn abort (&self) { self.abort_handle.abort() }
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
    fn receive (&mut self, msg: MsgType, hself: &ActorHandle<MsgType>) -> impl Future<Output = ReceiveAction> + Send;
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
        match timeout( to, self.send_actor_msg(msg)).await {
            Ok(result) => result,
            Err(_) => Err(OdinActorError::TimeoutError(to))
        }
    }

    pub async fn timeout_send_msg<M:Into<MsgType>> (&self, msg: M, to: Duration)->Result<()> {
        self.timeout_send_actor_msg( msg.into(), to).await
    }

    /// this returns immediately but the caller has to check if the message got sent
    pub fn try_send_actor_msg (&self, msg: MsgType)->Result<()> {
        self.tx.try_send(msg).map_err( |e| match e {
            mpsc::error::TrySendError::Full(_) => OdinActorError::ReceiverFull,
            mpsc::error::TrySendError::Closed(_) => OdinActorError::ReceiverClosed
        })
    }

    pub fn try_send_msg<M: Into<MsgType>> (&self, msg:M)->Result<()> {
        self.try_send_actor_msg(msg.into())
    }

    // TODO - is this right to skip if we can't send? Maybe that should be an option

    pub fn start_oneshot_timer (&self, id: i64, delay: Duration) -> Abortable {
        let h = self.clone();

        let th = spawn( async move {
            sleep(delay).await;
            h.try_send_actor_msg( _Timer_{id}.into() );
        });
        Abortable { abort_handle: th.abort_handle() }
    }

    pub fn start_repeat_timer (&self, id: i64, timer_interval: Duration) -> Abortable {
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
        Abortable { abort_handle: th.abort_handle() }
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

/// internal struct to 
struct ActorEntry {
    id: Arc<String>,
    type_name: &'static str,
    abortable: Abortable,          // this is a runtime specific type
    receiver: Box<dyn SysMsgReceiver>,
    ping_response: Arc<AtomicU64>
}

pub struct ActorSystem {
    id: String,
    join_set: task::JoinSet<()>,
    actors: Vec<ActorEntry>,
    ping_cycle: u32
}

impl ActorSystem {

    pub fn new<T: ToString> (id: T)->Self {
        ActorSystem { id: id.to_string(), join_set: task::JoinSet::new(), actors: Vec::new(), ping_cycle: 0 }
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

        let abort_handle = self.join_set.spawn( Self::run_actor(actor,hself,rx));

        let actor_entry = ActorEntry {
            id: actor_id.clone(),
            type_name: std::any::type_name::<ActorType>(),
            abortable: Abortable { abort_handle },
            receiver: Box::new(actor_handle.clone()),
            ping_response: Arc::new(AtomicU64::new(0))
        };

        self.actors.push( actor_entry);

        actor_handle
    }

    // the (internal) task function - consume the actor and loop while there are potential senders
    async fn run_actor <MsgType,ActorType> (mut actor: ActorType, hself: ActorHandle<MsgType>, mut rx: MpscReceiver<MsgType>)
        where 
            MsgType: FromSysMsg + DefaultReceiveAction + Send + Debug + 'static,
            ActorType: Actor<MsgType> + Send + 'static,
    {
        while let Some(msg) = rx.recv().await {
            if let ReceiveAction::Stop = actor.receive(msg,&hself).await {
                rx.close();
                break;
            }
        }
    }

    pub async fn wait_all (&mut self, to: Duration) -> Result<()> {
        let len = self.join_set.len();
        let mut closed = 0;
        while let Some(_res) = self.join_set.join_next().await {
            closed += 1;
        }
        closed == self.actors.len();
        
        if closed == len { Ok(()) } else { Err(all_op_failed("join", len, len-closed))}
    }

    pub fn abort_all(&mut self) {
        self.join_set.abort_all();
    }

    pub async fn ping_all (&mut self, to: Duration)->Result<()> {
        self.ping_cycle += 1;

        for actor_entry in &self.actors {
            let response = actor_entry.ping_response.clone();
            actor_entry.receiver.send_ping( _Ping_{ cycle: self.ping_cycle, sent: Instant::now(), response });
        }
        Ok(())
    }

    pub async fn start_all (&self, to: Duration)->Result<()> {
        let mut failed = 0;
        for actor_entry in &self.actors {
            if actor_entry.receiver.send_start(_Start_{}, to).await.is_err() { failed += 1 }
        }
        self.all_op_result("start_all", failed)
    }

    pub async fn terminate_all (&self, to: Duration)->Result<()> {
        let mut failed = 0;
        for actor_entry in &self.actors {
            if actor_entry.receiver.send_terminate(_Terminate_{}, to).await.is_err() { failed += 1 };
        }
        self.all_op_result("start_all", failed)
    }

    pub async fn terminate_and_wait (&mut self, to: Duration)->Result<()> {
        self.terminate_all( to).await;

        let res = self.wait_all(to).await;
        if (res.is_err()) {
            self.abort_all()
        }
        res
    }

    fn all_op_result (&self, op: &'static str, failed: usize)->Result<()> {
        if failed == 0 { Ok(()) } else { Err(all_op_failed("terminate_all", self.actors.len(), failed)) }
    }
}

/* #endregion ActorSystem */

/* #region message patterns *******************************************************************************/
/*
 * these functions are more complex and can use all our abstractions, but their interfaces are not
 * allowed to expose the concrete runtime/channel constructs since these functions are callable from
 * user (actor) code
 */


pub async fn ask <Q,A> (tgt: impl MsgReceiver<Q>)->Result<A>
    where
        Q: Respondable<A> + Send + 'static, 
        A: Send + 'static
{
    let (tx,rx) = create_oneshot_sender_receiver::<A>();
    let q = Respondable::create_question(tx);

    tgt.send_msg(q).await?;

    rx.await.map_err(|_| OdinActorError::SendersDropped)
}

/// create a question message of type Q, send it to tgt and timeout wait for an answer of type A.
/// this is the general pattern for synchronous message exchange 
pub async fn timeout_ask <Q,A> (tgt: impl MsgReceiver<Q>, to: Duration)->Result<A>
    where
        Q: Respondable<A> + Send + 'static, 
        A: Send + 'static
{
    let (tx,rx) = create_oneshot_sender_receiver::<A>();
    let q = Respondable::create_question(tx);

    tgt.timeout_send_msg(q, to).await?;

    match timeout( to, rx).await {
        Ok(result) => result.map_err(|_| OdinActorError::SendersDropped),
        Err(e) => Err(OdinActorError::TimeoutError(to))
    }
}

// a sync try_ask() does not make sense since we do have to await the response 

/* #endregion message patterns */