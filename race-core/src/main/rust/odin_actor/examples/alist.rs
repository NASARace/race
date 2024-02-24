#![allow(unused)]

use tokio;
use std::collections::VecDeque;
use std::{future::Future,sync::Arc};
use odin_actor::prelude::*;
use odin_actor::errors::{OdinActorError, Result};
use odin_actor::tokio_kanal::{create_mpsc_sender_receiver, sleep, spawn, 
    AbortHandle, Actor, ActorHandle, ActorSystem, ActorSystemHandle, JoinHandle, MpscSender};

/* #region updater ***************************************************************************/

// note - updater does not need to know anything about potential clients - it only feeds
// its data into provided callbacks
// Note also that async callbacks are not particularly efficient since they have to
// wrap opaque futures on each invocation. This is mostly tolerable (for now) because
// high frequent (update) callback executions probably use the sync try_send_msg_callback
// if the update data has a short lifespan

struct Updater<A1,A2> where A1: ActorActionList<Vec<String>>, A2: ActorAction2List<Vec<String>,String>
{
    data: Vec<String>,
    count: usize,
    update_actions: A1,
    init_action: A2
}
impl<A1,A2> Updater<A1,A2> where A1: ActorActionList<Vec<String>>, A2: ActorAction2List<Vec<String>,String>
{
    fn new(update_actions: A1, init_action: A2)->Self {
        Updater { data: Vec::new(), count: 0, update_actions, init_action }
    }

    fn update(&mut self) {
        let new_value = format!("{} Missisippi", self.count);
        self.data.push( new_value);
    }
}

#[derive(Debug)] struct ExecuteInitAction { addr: String }

define_actor_msg_type! { UpdaterMsg = ExecuteInitAction }

impl_actor! { match msg for Actor<Updater<A1,A2>,UpdaterMsg> 
                    where A1: ActorActionList<Vec<String>>, A2: ActorAction2List<Vec<String>,String>
    as
    _Start_ => cont! {
        self.hself.start_repeat_timer( 1, secs(1));
        println!("{} started update timer", self.hself.id);
    }
    _Timer_ => {
        self.count += 1;
        println!("update cycle {}", self.count);
        self.update();

        if self.count < 5 {
            self.update_actions.execute(&self.data).await;
            ReceiveAction::Continue
        } else {
            println!("{} had enough of it, request termination.", self.hself.id); 
            ReceiveAction::RequestTermination 
        }
    }
    
    ExecuteInitAction => cont! {
        println!("updater received {msg:?}");
        self.init_action.execute( &self.data, &msg.addr).await;
    }

}

/* #endregion updater */

/* #region server *********************************************************************************/
struct WsServer {} 

// these message types are too 'WsServer' specific to be forced upon a generic, reusable Updater

#[derive(Debug)] struct PublishWsMsg { ws_msg: String }

#[derive(Debug)] struct SendWsMsg { addr: String, ws_msg: String }

define_actor_msg_type! { WsServerMsg = PublishWsMsg | SendWsMsg }

impl_actor! { match msg for Actor<WsServer,WsServerMsg> as
    PublishWsMsg => cont! {
        println!("WsServer publishing data '{}' to all its connections", msg.ws_msg);
    }
    SendWsMsg => cont! {
        println!("WsServer sending init data '{}' to connection '{}'", msg.ws_msg, msg.addr);
    }
}

/* #endregion server */

#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let server = spawn_actor!( actor_system, "server", WsServer{}, 4)?;

    define_actor_action_list!{ for actor_handle in UpdateActions (v: &Vec<String>) :
        WsServerMsg => actor_handle.try_send_msg( PublishWsMsg{ws_msg: format!("{{\"update\": {v:?}}}")})
    }
    define_actor_action2_list! { for actor_handle in InitAction (v: &Vec<String>, addr: &String):
        WsServerMsg => {
            let msg = SendWsMsg{ addr: addr.clone(), ws_msg: format!("{v:?}")};
            actor_handle.try_send_msg( msg)
        }
    }

    let updater = spawn_actor!( actor_system, "updater", Updater::new( UpdateActions(server.clone()), InitAction(server.clone())))?;

    updater.send_msg( ExecuteInitAction{addr: "forty_two".to_string()}).await?;

    actor_system.start_all(millis(20)).await?;

    sleep( secs(2)).await;

    updater.send_msg( ExecuteInitAction{addr:"forty_three".to_string()}).await?;

    actor_system.process_requests().await?;

    Ok(())
}
