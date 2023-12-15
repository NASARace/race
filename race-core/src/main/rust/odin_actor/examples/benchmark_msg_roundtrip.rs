#![allow(unused)]

///! while this is a benchmark by nature it is not in the benches/ dir since it does not use the Rust micro-benchmark
///! infrastructure and is not run with `cargo bench`. In general we try to avoid micro-benchmarking async code

use odin_actor::prelude::*;
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle};

use std::time::{Instant,Duration};
use std::sync::Mutex;

const MAX_CYCLE: u64 = 10_000_000;

static ELASPED_ROUNDTRIP: Mutex<Duration> = Mutex::new( Duration::new(0,0));

#[derive(Debug)]
pub struct Cycle;

define_actor_msg_type! {
    TestMsg = Cycle
}

struct TestActorState {
    start_time: Instant,
    round: u64
}

impl_actor! { match msg: TestMsg for TestActorState as
    Cycle => {
        if self.state.round < MAX_CYCLE {
            if self.state.round == 0 { 
                self.state.start_time = Instant::now()
            }
            self.state.round += 1;
            self.hself.try_send_msg(Cycle{}); // since this is the only send there can't be more than one message in the queue
            ReceiveAction::Continue
            
        } else {
            let now = Instant::now();
            let elapsed = now - self.state.start_time;
            *ELASPED_ROUNDTRIP.lock().unwrap() += elapsed;
            ReceiveAction::RequestTermination
        }
    }
}

//#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
#[tokio::main]
pub async fn main()->std::result::Result<(),Box<dyn std::error::Error>> {
    println!("-- running raw_bench with {} rounds", MAX_CYCLE);
    let start = Instant::now();

    let mut actor_system = ActorSystem::new("raw_msg");
    let a = spawn_actor!( actor_system, "test_actor", TestActorState { start_time: Instant::now(), round: 0 })?;

    a.try_send_msg( Cycle{})?;
    actor_system.process_requests().await?;

    let elapsed = (Instant::now() - start);
    let elapsed_roundtrip = ELASPED_ROUNDTRIP.lock().unwrap();
    println!("{} message roundtrips in {} μs -> {} ns/msg-roundtrip", 
               MAX_CYCLE, elapsed_roundtrip.as_micros(), (elapsed_roundtrip.as_nanos() as u64 / MAX_CYCLE));
    println!("total time to create and run actor system: {} μs -> overhead: {} μs", 
                elapsed.as_micros(), (elapsed - *elapsed_roundtrip).as_micros());

    Ok(())
}