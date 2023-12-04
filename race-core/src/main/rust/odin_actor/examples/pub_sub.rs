#![allow(unused)]

use odin_actor::prelude::*;
use odin_actor::errors::Result;
//use odin_actor::tokio_channel::{ActorSystem,Actor,ActorHandle,Abortable, sleep};
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,AbortHandle,sleep};
use std::{sync::Arc, future::Future, pin::Pin};

#[derive(Debug,Clone)] struct Update(u64);

#[derive(Debug)] struct Subscribe(Subscriber<Update>);

/* #region Updater ********************************************************/

define_actor_msg_set!( enum UpdaterMsg { Subscribe });

struct Updater {
    subscribers: Subscriptions<Update>,
    count: u64,
    timer: Option<AbortHandle>
}
impl Updater {
    fn new ()->Self {
        Updater { subscribers: Subscriptions::new(), count: 0, timer: None }
    }
}

impl Actor<UpdaterMsg> for Updater {
    async fn receive (&mut self, msg: UpdaterMsg, hself: &ActorHandle<UpdaterMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: UpdaterMsg as
            _Start_ => cont! {
                self.timer = Some(hself.start_repeat_timer( 1, secs(1)));
                println!("{} started update timer", hself.id);
            }
            _Timer_ => {
                self.count += 1;
                if self.count < 5 {
                    self.subscribers.publish_msg( Update(self.count)).await;
                    ReceiveAction::Continue
                } else {
                    println!("{} had enough of it, request termination.", hself.id); 
                    ReceiveAction::RequestTermination 
                }
            }
            Subscribe => cont! {
                println!("got new subscription: {:?}", msg);
                self.subscribers.add( msg.0)
            }
        }
    }
}

/* #endregion Updater */

/* #region Client ********************************************************/

define_actor_msg_set!( enum ClientMsg { Update });

struct Client;

impl Actor<ClientMsg> for Client {
    async fn receive (&mut self, msg: ClientMsg, hself: &ActorHandle<ClientMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: ClientMsg as
            Update => cont! { println!("{} got {:?}", hself.id, msg) }
        }
    }
}

/* #endregion Client */


#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let updater = actor_system.actor_of( Updater::new(), 8, "updater" );
    let client_1 = actor_system.actor_of( Client{}, 8, "client_1");
    let client_2 = actor_system.actor_of( Client{}, 8, "client_2");

    updater.send_msg( Subscribe( subscriber(client_1))).await;
    updater.send_msg( Subscribe( subscriber(client_2))).await;

    actor_system.start_all(millis(20)).await?;
    actor_system.process_requests().await
}