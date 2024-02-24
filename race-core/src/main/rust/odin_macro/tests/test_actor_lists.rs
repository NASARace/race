#![allow(unused)]

use std::{error::Error, marker::PhantomData};
use std::fmt::Debug;
use std::future::Future;

use odin_macro::{define_actor_send_msg_list, define_actor_action_list, define_actor_action2_list};

/* #region framework mockup ***************************************************************************/

mod odin_actor { // mockup - the trait impls use it
    pub type Result<T> = std::result::Result<T,Box<dyn std::error::Error>>; 
}

type Result<T> = odin_actor::Result<T>;

trait MsgReceiver<T> where T: Send+Debug {
    async fn send_msg(&self, m:T)->Result<()>;
    fn try_send_msg(&self, m:T)->Result<()>;
}

struct ActorHandle<M> where M: Send+Debug {
    id: &'static str, 
    _phantom: PhantomData<M> 
}
impl<M> ActorHandle<M> where M: Send+Debug {
    fn new(id:&'static str)->Self { ActorHandle{ id, _phantom: PhantomData }}

    async fn send_actor_msg (&self, m:M)->Result<()> {
        println!("async sending {:?} to actor {}", m, self.id);
        Ok(())
    }
    fn try_send_actor_msg (&self, m:M)->Result<()> {
        println!("try_sending {:?} to actor {}", m, self.id);
        Ok(())
    }
}

impl <T,M> MsgReceiver<T> for ActorHandle<M> where T: Send+Debug, M: From<T>+Send+Debug {
    async fn send_msg(&self, m:T)->Result<()> { self.send_actor_msg( m.into()).await }
    fn try_send_msg(&self, m:T)->Result<()> { self.try_send_actor_msg( m.into()) }
}

pub trait ActorActionList<A> {
    fn execute (&self,data:&A) -> impl Future<Output=Result<()>>;
}

pub trait ActorAction2List<A,B> {
    fn execute (&self,data1:&A,data2:&B) -> impl Future<Output=Result<()>>;
}

pub trait ActorSendMsgList<M> {
    fn send_msg (&self,msg:M) -> impl Future<Output=Result<()>>;
}

/* #endregion framework mockup */

//--- app mockup
#[derive(Debug,Clone)] struct Msg1(u64);
#[derive(Debug,Clone)] struct Msg2(u64);
#[derive(Debug,Clone)] struct Msg3(String);

#[derive(Debug,Clone)] enum Actor1Msg { Msg1(Msg1), Msg3(Msg3) }
impl From<Msg1> for Actor1Msg { fn from(m:Msg1)->Actor1Msg {Actor1Msg::Msg1(m)} }
impl From<Msg3> for Actor1Msg { fn from(m:Msg3)->Actor1Msg {Actor1Msg::Msg3(m)} }

#[derive(Debug,Clone)] enum Actor2Msg { Msg1(Msg1), Msg2(Msg2) }
impl From<Msg1> for Actor2Msg { fn from(m:Msg1)->Actor2Msg {Actor2Msg::Msg1(m)} }
impl From<Msg2> for Actor2Msg { fn from(m:Msg2)->Actor2Msg {Actor2Msg::Msg2(m)} }

#[derive(Debug)] struct SomeData(u64);

#[test]
fn test_action_list() {
    let ah1 = ActorHandle::<Actor1Msg>::new("actor-1");
    let ah2 = ActorHandle::<Actor2Msg>::new("actor-2");

    define_actor_action_list!{ for actor_handle in MyActions (data: &SomeData) :
        Actor1Msg => actor_handle.send_msg( Msg1(data.0)).await,
        Actor2Msg => actor_handle.try_send_msg( Msg2(data.0))
    }
    let my_actions = MyActions( ah1, ah2);
    println!("got my actions."); // this is needed if we want to print debug output (--nocapture)
}

#[test]
fn test_action2_list() {
    let ah1 = ActorHandle::<Actor1Msg>::new("actor-1");

    define_actor_action2_list!{ for actor_handle in MyActions (own: &SomeData, ext: &i64) :
        Actor1Msg => {
            let s = format!("executed with {:?} and {}", own, ext);
            actor_handle.try_send_msg( Msg3(s))
        }
    }
    let my_actions = MyActions( ah1);
    println!("got my actions."); // this is needed if we want to print debug output (--nocapture)
}

#[test]
fn test_send_msg_list() {
    let ah1 = ActorHandle::<Actor1Msg>::new("actor-1");
    let ah2 = ActorHandle::<Actor2Msg>::new("actor-2");

    define_actor_send_msg_list!{ MySends (Msg1) : Actor1Msg, Actor2Msg }
    let my_sends = MySends( ah1, ah2);
    println!("got my sends."); // this is needed if we want to print debug output (--nocapture)
}