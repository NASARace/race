#![allow(unused)]

use odin_actor::prelude::*;
//use odin_actor::tokio_channel::{ActorSystem,Actor,ActorHandle,Abortable, sleep};
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,sleep};

use anyhow::{anyhow,Result};

/* #region producer *********************************************************************/

#[derive(Debug)]
pub struct MyMsg(i64);

// we don't need to know anything about the client other than it processes MyMsg
pub struct Producer<Client> where Client: MsgReceiver<MyMsg> {
    client: Client
}

//#[impl_actor(Producer<Client:MsgReceiver<MyMsg>>)]
impl <Client> Actor<SysMsg> for Producer<Client> where Client: MsgReceiver<MyMsg> + Send {
    async fn receive (&mut self, msg: SysMsg, hself: &ActorHandle<SysMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: SysMsg as
            _Start_ => request_termination! { 
                let msg = MyMsg(42);
                println!("producer started, sent {:?} and requests termination", msg);
                self.client.send_msg( msg).await.unwrap();
            }
            _Terminate_ => stop! {
                println!("producer terminated");
            }
        }
    }
}

/* #endregion producer */

/* #region consumer *********************************************************************/
define_actor_msg_set!( enum ConsumerMsg {MyMsg});

pub struct Consumer;

impl Actor<ConsumerMsg> for Consumer {
    async fn receive (&mut self, msg: ConsumerMsg, hself: &ActorHandle<ConsumerMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: ConsumerMsg as
            MyMsg => cont! { println!("consumer got a {:?}", msg); }
        }
    }
}

/* #region consumer */

#[tokio::main]
async fn main() ->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let consumer_handle = actor_system.actor_of( Consumer{}, 8, "consumer");
    let producer_handle = actor_system.actor_of( Producer {client: consumer_handle}, 8, "producer");

    actor_system.start_all(millis(20)).await?; // sends out _Start_ messages
    actor_system.process_requests().await?;

    Ok(())
}