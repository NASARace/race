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
use std::future::Future;
use odin_actor::{prelude::*};
use odin_actor::errors::Result;
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,AbortHandle,sleep};

/* #region updater ***************************************************************************/
struct Updater {
    update_callbacks: CallbackList<u64>,
    count: u64,
}
impl Updater {
    fn new()->Self {
        Updater { update_callbacks: CallbackList::new(), count: 0 }
    }
}

#[derive(Debug)] struct AddUpdateCallback { id: String, action: Callback<u64> }

#[derive(Debug)] struct TriggerCallback(Callback<u64>);
impl TriggerCallback {
    pub fn trigger (&self, data: u64)->impl Future<Output=Result<()>> {
        self.0.trigger(data)
    }
}

define_actor_msg_type! { UpdaterMsg = AddUpdateCallback | TriggerCallback }

impl_actor! { match msg for Actor<Updater,UpdaterMsg> as
    _Start_ => cont! {
        self.hself.start_repeat_timer( 1, secs(1));
        println!("{} started update timer", self.hself.id);
    }
    _Timer_ => {
        println!("update cycle {}", self.count);
        self.count += 1;
        if self.count < 5 {
            self.update_callbacks.trigger(self.count).await;
            ReceiveAction::Continue
        } else {
            println!("{} had enough of it, request termination.", self.hself.id); 
            ReceiveAction::RequestTermination 
        }
    }
    AddUpdateCallback => cont! {
        self.update_callbacks.add( msg.id, msg.action )
    }
    TriggerCallback => cont! {
        println!("updater received {msg:?}");
        msg.trigger(self.count).await;
    }
}

/* #endregion updater */

/* #region server *********************************************************************************/
struct Server {} 

// these message types are too 'Server' specific to be forced upon a generic, reusable Updater

#[derive(Debug)] struct PublishWsMsg { data: u64 }

#[derive(Debug)] struct SendWsMsg { addr: &'static str, data: u64 }

define_actor_msg_type! { ServerMsg = PublishWsMsg | SendWsMsg }

impl_actor! { match msg for Actor<Server,ServerMsg> as
    PublishWsMsg => cont! {
        println!("server publishing data {} to all connections", msg.data);
    }
    SendWsMsg => cont! {
        println!("server sending data {} to connection '{}'", msg.data, msg.addr);
    }
}

/* #endregion server */


#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let updater = spawn_actor!( actor_system, "updater", Updater::new())?;
    let server = spawn_actor!( actor_system, "server", Server{})?;

    // note how we construct the action (callback) from a mix of sender/local (server, addr) and receiver/remote (data) info
    let addr = "fortytwo";
    let action = send_msg_callback!( server <- |data: u64| SendWsMsg{ addr, data});
    updater.send_msg( TriggerCallback( action)).await?;

    let action = send_msg_callback!( server <- |data: u64| PublishWsMsg{data});
    updater.send_msg( AddUpdateCallback{id: "server".to_string(), action} ).await?;

    let action = sync_callback!( |data:u64| { println!("spooky sync action from a distance with {data}"); Ok(()) } );
    updater.send_msg( TriggerCallback( action)).await?;

    let action = async_callback!( |data: u64| async_action(data) );
    updater.send_msg( TriggerCallback( action)).await?;

    actor_system.start_all(millis(20)).await?;
    actor_system.process_requests().await?;

    Ok(())
}

async fn async_action (data: u64)->Result<()> {
    sleep( secs(2)).await;
    println!("async action with {data}");
    Ok(())
}