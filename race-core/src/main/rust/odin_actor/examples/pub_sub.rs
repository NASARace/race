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

use odin_actor::prelude::*;
use odin_actor::errors::Result;
//use odin_actor::tokio_channel::{ActorSystem,Actor,ActorHandle,Abortable, sleep};
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,AbortHandle,sleep};

use std::{sync::Arc, future::Future, pin::Pin};

#[derive(Debug,Clone)] struct Update(u64);

#[derive(Debug)] struct Subscribe(MsgSubscriber<Update>);

/* #region Updater ********************************************************/

define_actor_msg_type! { UpdaterMsg = Subscribe }

struct Updater {
    subscribers: MsgSubscriptions<Update>,
    count: u64,
    timer: Option<AbortHandle>
}

impl Updater {
    fn new ()->Self {
        Updater { subscribers: MsgSubscriptions::new(), count: 0, timer: None }
    }
}

impl_actor! { match msg for Actor<Updater,UpdaterMsg> as
    _Start_ => cont! {
        self.timer = Some(self.hself.start_repeat_timer( 1, secs(1)));
        println!("{} started update timer", self.hself.id);
    }
    _Timer_ => {
        self.count += 1;
        if self.count < 5 {
            self.subscribers.publish_msg( Update(self.count)).await;
            ReceiveAction::Continue
        } else {
            println!("{} had enough of it, request termination.", self.hself.id); 
            ReceiveAction::RequestTermination 
        }
    }
    Subscribe => cont! {
        println!("got new subscription: {:?}", msg);
        self.subscribers.add( msg.0)
    }
}

/* #endregion Updater */

/* #region Client ********************************************************/

define_actor_msg_type! { ClientMsg = Update }

struct Client;

impl_actor! { match msg for Actor<Client,ClientMsg> as
    Update => cont! { println!("{} got {:?}", self.hself.id, msg) }
}

/* #endregion Client */


#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let updater = spawn_actor!( actor_system, "updater", Updater::new())?;
    let client_1 = spawn_actor!( actor_system, "client-1", Client{})?;
    let client_2 = spawn_actor!( actor_system, "client-2", Client{})?;

    updater.send_msg( Subscribe( msg_subscriber(client_1))).await;
    updater.send_msg( Subscribe( msg_subscriber(client_2))).await;

    actor_system.timeout_start_all(millis(20)).await?;
    actor_system.process_requests().await
}