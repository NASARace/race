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

struct Updater {
    data: Vec<String>,
    count: usize,
    update_callbacks: CallbackList<Vec<String>>,
}
impl Updater {
    fn new()->Self {
        Updater { data: Vec::new(), count: 0, update_callbacks: CallbackList::new() }
    }

    fn update(&mut self) {
        let new_value = format!("{} Missisippi", self.count);
        self.data.push( new_value);
    }
}

#[derive(Debug)] struct AddUpdateCallback(Callback<Vec<String>>);

#[derive(Debug)] struct ExecuteCallback(Arc<Callback<Vec<String>>>);
impl ExecuteCallback {
    pub async fn execute (&self, data: &Vec<String>)->Result<()> {
        self.0.execute(data).await
    }
}

define_actor_msg_type! { UpdaterMsg = AddUpdateCallback | ExecuteCallback }

impl_actor! { match msg for Actor<Updater,UpdaterMsg> as
    _Start_ => cont! {
        self.hself.start_repeat_timer( 1, secs(1));
        println!("{} started update timer", self.hself.id);
    }
    _Timer_ => {
        self.count += 1;
        println!("update cycle {}", self.count);
        self.update();

        if self.count < 5 {
            self.update_callbacks.execute(&self.data).await;
            ReceiveAction::Continue
        } else {
            println!("{} had enough of it, request termination.", self.hself.id); 
            ReceiveAction::RequestTermination 
        }
    }
    AddUpdateCallback => cont! {
        self.update_callbacks.push( msg.0)
    }
    
    ExecuteCallback => cont! {
        println!("updater received {msg:?}");
        msg.execute(&self.data).await;
    }

}

/* #endregion updater */

/* #region server *********************************************************************************/
struct WsServer {} 

// these message types are too 'WsServer' specific to be forced upon a generic, reusable Updater

#[derive(Debug)] struct PublishWsMsg { ws_msg: String }

#[derive(Debug)] struct SendWsMsg { addr: &'static str, ws_msg: String }

define_actor_msg_type! { WsServerMsg = PublishWsMsg | SendWsMsg }

impl_actor! { match msg for Actor<WsServer,WsServerMsg> as
    PublishWsMsg => cont! {
        println!("WsServer publishing data '{}' to all its connections", msg.ws_msg);
    }
    SendWsMsg => cont! {
        println!("WsServer sending data '{}' to connection '{}'", msg.ws_msg, msg.addr);
    }
}

/* #endregion server */

#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let updater = spawn_actor!( actor_system, "updater", Updater::new())?;
    let server = spawn_actor!( actor_system, "server", WsServer{}, 4)?;

    // note how we construct the callback from a mix of captured sender/local (server, addr) and passed in receiver/remote (data) info
    let addr = "fortytwo";
    /* explicit version
    let recipient = server.clone();
    let trigger_cb: Arc<Callback<Vec<String>>> = Arc::new( async_callback!( |data: &Vec<String>| {
        let ws_msg = format!("{:?}", data);
        let recipient = recipient.clone(); // FIXME
        async_action!( recipient.send_msg( SendWsMsg{addr, ws_msg}).await )
    }).into());
    */
    let trigger_cb = Arc::new(Callback::from(send_msg_callback!(server, |v: &Vec<String>| SendWsMsg{addr, ws_msg: format!("{v:?}")})));

    updater.send_msg( ExecuteCallback( trigger_cb.clone())).await?;

    /* explicit version
    let recipient = server.clone();
    let update_cb = async_callback!( |data: &Vec<String>| {
        let ws_msg = format!("{{\"update\": {:?}}}", data);
        let recipient = recipient.clone(); // FIXME
        async_action!( recipient.send_msg(PublishWsMsg{ws_msg}).await )
    });
    */
    let update_cb = try_send_msg_callback!(server, |v: &Vec<String>| PublishWsMsg{ws_msg: format!("{{\"update\": {v:?}}}")});

    updater.send_msg( AddUpdateCallback(update_cb.into())).await?;

    actor_system.start_all(millis(20)).await?;

    sleep( secs(2)).await;

    updater.send_msg( ExecuteCallback( trigger_cb)).await?;

    actor_system.process_requests().await?;

    Ok(())
}
