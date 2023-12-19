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

///! while this is a benchmark by nature it is not in the benches/ dir since it does not use the Rust micro-benchmark
///! infrastructure and is not run with `cargo bench`. In general we try to avoid micro-benchmarking async code

use odin_actor::prelude::*;
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle};

use std::time::{Instant,Duration};
use std::sync::Mutex;

static ELASPED_ROUNDTRIP: Mutex<Duration> = Mutex::new( Duration::new(0,0));

#[derive(Debug)]
pub struct Cycle { start_time: Instant, round: u64 }

define_actor_msg_type! { TestMsg = Cycle }

struct Test { max_rounds: u64 } // our actor state

impl_actor! { match msg for Actor<Test,TestMsg> as
    Cycle => {
        if msg.round < self.max_rounds {
            self.try_send_msg( Cycle{round: msg.round+1, ..msg} );
            ReceiveAction::Continue
            
        } else {
            let now = Instant::now();
            let elapsed = now - msg.start_time;
            *ELASPED_ROUNDTRIP.lock().unwrap() += elapsed;
            println!("{} message roundtrips in {} μs -> {} ns/msg-roundtrip", 
                    self.max_rounds, elapsed.as_micros(), (elapsed.as_nanos() as u64 / self.max_rounds));
            ReceiveAction::RequestTermination
        }
    }
}

//#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
#[tokio::main]
pub async fn main()->std::result::Result<(),Box<dyn std::error::Error>> {
    let max_rounds = get_max_rounds();
    println!("-- running raw_bench with {} rounds", max_rounds);
    let start = Instant::now();

    let mut actor_system = ActorSystem::new("raw_msg");
    let a = spawn_actor!( actor_system, "test_actor", Test{max_rounds})?;

    a.try_send_msg( Cycle{ start_time: Instant::now(), round: 0 })?;
    actor_system.process_requests().await?;

    let elapsed = (Instant::now() - start);
    let elapsed_roundtrip = ELASPED_ROUNDTRIP.lock().unwrap();

    println!("total time to create and run actor system: {} μs -> overhead: {} μs", 
                elapsed.as_micros(), (elapsed - *elapsed_roundtrip).as_micros());

    Ok(())
}

fn get_max_rounds()->u64 {
    let args: Vec<String> = std::env::args().collect();
    if args.len() == 1 {
        1_000_000 // our default value
    } else {
        args[1].parse().expect("max round argument not an integer")
    }
}