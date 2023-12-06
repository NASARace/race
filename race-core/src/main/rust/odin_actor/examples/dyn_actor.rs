#![allow(unused)]

use odin_actor::prelude::*;
use odin_actor::tokio_channel::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,sleep};
//use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,sleep};
use std::{sync::Arc, future::Future};
use anyhow::{anyhow,Result};

/* #region ChildActor ************************************************** */

#[derive(Debug)] pub struct ChildRequest { data: i64 }
define_actor_msg_set!( pub enum ChildMsg {ChildRequest});

#[derive(Clone)]
struct ChildActor<A: MsgReceiver<ChildResponse>> { parent:  A }

impl<A> Actor<ChildMsg> for ChildActor<A> where A: MsgReceiver<ChildResponse> + Send
{
    async fn receive (&mut self, msg: ChildMsg, hself: &ActorHandle<ChildMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: ChildMsg as 
            ChildRequest => cont! {
                sleep(secs(1)).await;
                let reply = if msg.data == 42 {"I know the answer"} else {"I don't know the answer"};
                self.parent.send_msg( ChildResponse(String::from(reply))).await.unwrap()
            }
        }
    }
}

/* #endregion ChildActor */

/* #region ParentActor ************************************************** */

#[derive(Debug)] pub struct ChildResponse(String);
define_actor_msg_set!( pub enum ParentMsg {ChildResponse});

struct ParentActor {
    child_handle: Option<ActorHandle<ChildMsg>>
}
impl ParentActor {
    fn new ()->Self {
        ParentActor { child_handle: None }
    }

    async fn create_child_actor (&self, hself: &ActorHandle<ParentMsg>, hsys: &ActorSystemHandle)->Option<ActorHandle<ChildMsg>> {
        if let Ok(child_handle) = hsys.actor_of( ChildActor { parent: hself.clone() }, 8, "child").await {
            println!("created child actor: {:?}", child_handle);
            Some(child_handle)
        } else {
            None
        }
    }
}

impl Actor<ParentMsg> for ParentActor {
    async fn receive (&mut self, msg: ParentMsg, hself: &ActorHandle<ParentMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: ParentMsg as
            _Start_ => cont! { 
                if let Some(child_handle) = self.create_child_actor( hself, hsys).await {
                    child_handle.send_msg(ChildRequest {data:42}).await.unwrap()
                }
            }
            ChildResponse => request_termination! {
                println!("got answer: {:?}", msg)
            }
        }
    }
}

/* #endregion ParentActor */

#[tokio::main]
async fn main() ->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let parent_actor = ParentActor::new();
    let actor_handle = actor_system.actor_of( parent_actor, 8, "parent");
    
    actor_system.start_all(millis(20)).await?; // sends out _Start_ messages
    actor_system.process_requests().await?;

    Ok(())
}