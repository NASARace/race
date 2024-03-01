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

use odin_actor::prelude::*;
use odin_actor::errors::Result;
use odin_actor::{tokio_kanal::{ActorSystem,Actor,ActorHandle,PreActorHandle}, MsgReceiver};

/// this example shows how to use PreActorHandles to break cyclic dependencies

#[derive(Debug)] pub struct Ping(u64);
#[derive(Debug)] pub struct Pong(u64);

//--- the Pinger

define_actor_msg_type! { PingerMsg = Pong }

struct Pinger<P> where P: MsgReceiver<Ping> {
    ponger: P
}

impl_actor! { match msg for Actor<Pinger<P>,PingerMsg> where P: MsgReceiver<Ping> as
    _Start_ => cont! {
        self.ponger.try_send_msg( Ping(0));
    }
    Pong => term! {
        println!("pinger got {msg:?}");
    }
}

//--- the Ponger

define_actor_msg_type! { PongerMsg = Ping }

struct Ponger<P> where P: MsgReceiver<Pong> {
    pinger: P
}

impl_actor! { match msg for Actor<Ponger<P>,PongerMsg> where P: MsgReceiver<Pong> as
    Ping => term! {
        println!("ponger got {msg:?}");
        self.pinger.try_send_msg( Pong( msg.0))
    }
}

//--- the application

#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let pre_hpong = PreActorHandle::new( &actor_system, "ponger", 8);
    let hping = spawn_actor!( actor_system, "pinger", Pinger{ponger: pre_hpong.as_actor_handle()})?;
    let hpong = spawn_pre_actor!( actor_system, pre_hpong, Ponger{pinger: hping})?;

    actor_system.timeout_start_all(millis(20)).await?;
    actor_system.process_requests().await
}