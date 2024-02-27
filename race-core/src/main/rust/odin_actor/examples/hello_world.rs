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
use anyhow::{anyhow,Result};

#[derive(Debug)]
pub struct Greet (&'static str);
//... define any other message struct our actor would process here
define_actor_msg_type! { GreeterMsg = Greet }

struct Greeter; // look ma - no fields

impl_actor! { match msg for Actor<Greeter,GreeterMsg> as
    Greet => cont! { println!("hello {}!", msg.0); }
}

#[tokio::main]
async fn main() ->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let actor_handle = spawn_actor!( actor_system, "greeter", Greeter{})?;

    actor_handle.send_msg( Greet("world")).await?;
    actor_handle.send_msg( Greet("me")).await?;

    actor_system.terminate_and_wait( secs(5)).await?;

    Ok(())
}
