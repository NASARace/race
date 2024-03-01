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

/// example of how to use the JobScheduler to send messages from closures

use odin_actor::prelude::*;
use odin_job::JobScheduler;
use odin_macro::fn_mut;
use anyhow::{anyhow,Result};

#[derive(Debug)] pub struct Greet (&'static str);
#[derive(Debug)] pub struct Tick(usize);
define_actor_msg_type! { GreeterMsg = Greet | Tick }

struct OnceGreeter { count: usize }

impl_actor! { match msg for Actor<OnceGreeter,GreeterMsg> as
    _Start_ => cont! {
        if let Ok(mut scheduler) = self.get_scheduler() {
            println!("scheduling job to run in 2 sec..");
            scheduler.schedule_once( secs(2), {
                let hself = self.hself.clone(); 
                move |_|{
                    println!("now sending message from scheduled job");
                    hself.try_send_msg( Greet("myself"));
                }
            });

            // alternative using syntactic sugar from odin_macro::fn_mut!(..)
            scheduler.schedule_repeated( secs(3), secs(1), fn_mut!( (hself=self.hself.clone(), mut tick=1) => |_ctx| {
                hself.try_send_msg(Tick(tick));
                tick += 1
            }));
        }
    }
    Greet => cont! { println!("hello {}!", msg.0); }
    Tick => {
        self.count += 1;
        println!("got {msg:?}");
        if self.count < 5 { ReceiveAction::Continue } else {
            println!("I've had enough Ticks - terminating.");
            ReceiveAction::RequestTermination
        }
    }
}


#[tokio::main]
async fn main() ->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let actor_handle = spawn_actor!( actor_system, "greeter", OnceGreeter{count:0})?;
    actor_system.start_all().await?;
    actor_system.process_requests().await;

    Ok(())
}