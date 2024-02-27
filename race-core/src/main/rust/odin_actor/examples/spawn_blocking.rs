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

use std::thread;

#[derive(Debug)] struct Spawn;
#[derive(Debug,Clone)] struct DataAvailable(i64);

define_actor_msg_type! { SpawnerMsg = Spawn | DataAvailable }

struct Spawner {
    count: usize,
    timer: Option<AbortHandle>,
    task: Option<JoinHandle<()>>
}
impl Spawner {
    fn new ()->Self { Spawner{ count: 0, timer: None, task: None } }

    fn run_task (max_cycles: i64)->i64 {
        let mut n = 0;
        println!("      task running..");
        while n < max_cycles {
            n += 1;
            thread::sleep(millis(1500));   // this is blocking the current thread
            println!("      task cycle {}", n);
        }
        println!("      task done.");
        n
    }
}

impl_actor! { match msg for Actor<Spawner,SpawnerMsg> as
    _Start_ => cont! { 
        self.timer = Some(self.hself.start_repeat_timer( 1, millis(1000)));
        println!("started timer");
    }
    _Timer_ => cont! { 
        self.count += 1;
        println!("tick {}", self.count);
        if self.count > 15 { 
            println!("spawner had enough, terminating..");
            self.hsys.request_termination(millis(500)).await; 
        }
    }
    Spawn => cont! {
        let hself = self.hself.clone();
        let max_cycles = 5;

        self.task = Some(
            spawn_blocking( move || {
                let result = Spawner::run_task(max_cycles);

                //--- there are several mechanisms to get back to the actor:
                //block_on( hself.send_msg( DataAvailable(result)));
                block_on( timeout(millis(100), hself.send_msg( DataAvailable(result)))); // to make sure the thread is not blocked indefinitely
                //hself.try_send_msg( DataAvailable(result)); // non-async alternative but might fail with backpressure
                //block_on_send_msg( hself, DataAvailable(result)); // specialized blocking (sleep) loop
                //block_on_timeout_send_msg( hself, DataAvailable(result), millis(100));
            })
        )
    }
    DataAvailable => cont! {
        println!("{} actor got {:?}", self.hself.id, msg)
    }
    _Terminate_ => stop! {
        if let Some(timer) = &self.timer { 
            timer.abort();
            self.timer = None;
        }
        if let Some(task) = &self.task {
            task.abort();
            self.task = None;
        }
        println!("{} terminated", self.hself.id);
    }
}


#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let spawner = spawn_actor!( actor_system, "spawner", Spawner::new())?;

    actor_system.timeout_start_all(millis(20)).await?;
    sleep(millis(2000)).await;
    spawner.send_msg(Spawn{}).await;

    actor_system.process_requests().await
}