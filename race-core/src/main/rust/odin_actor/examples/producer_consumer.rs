#![allow(unused)]

use odin_actor::prelude::*;
//use odin_actor::tokio_channel::{ActorSystem,Actor,ActorHandle,Abortable, sleep};
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,sleep};

use anyhow::{anyhow,Result};

/* #region producer *********************************************************************/

#[derive(Debug)]
pub struct MyMsg(i64);

define_actor_msg_type! { ProducerMsg }

// we don't need to know anything about the client other than it processes MyMsg
pub struct Producer<Client> where Client: MsgReceiver<MyMsg> {
    client: Client
}

impl_actor! { match msg for Actor<Producer<T>,ProducerMsg> where T: MsgReceiver<MyMsg> as
    _Start_ => term! { 
        let msg = MyMsg(42);
        println!("producer started, sent {:?} and requests termination", msg);
        self.client.send_msg( msg).await.unwrap();
    }
    _Terminate_ => stop! {
        println!("producer terminated");
    }
}

/* #endregion producer */

/* #region consumer *********************************************************************/
define_actor_msg_type! { ConsumerMsg = MyMsg }

pub struct Consumer;

impl_actor! { match msg for Actor<Consumer,ConsumerMsg> as 
    MyMsg => cont! { println!("consumer got a {:?}", msg); }
}

/* #region consumer */

#[tokio::main]
async fn main() ->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let consumer_handle = spawn_actor!( actor_system, "consumer", Consumer{})?;
    let producer_handle = spawn_actor!( actor_system, "producer", Producer {client: consumer_handle})?;

    actor_system.start_all(millis(20)).await?; // sends out _Start_ messages
    actor_system.process_requests().await?;

    Ok(())
}