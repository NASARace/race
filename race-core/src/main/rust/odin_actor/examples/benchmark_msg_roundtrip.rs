#![allow(unused)]

///! while this is a benchmark by nature it is not in the benches/ dir since it does not use the Rust micro-benchmark
///! infrastructure and is not run with `cargo bench`. In general we try to avoid micro-benchmarking async code

use odin_actor::prelude::*;
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle};
use std::time::{Instant,Duration};
use std::sync::Mutex;

const MAX_CYCLE: u64 = 5_000_000;

static ELASPED_ROUNDTRIP: Mutex<Duration> = Mutex::new( Duration::new(0,0));

#[derive(Debug)]
pub struct Cycle;

define_actor_msg_set!( pub enum TestMsg {Cycle} );

struct TestActor {
    start_time: Instant,
    round: u64
}

impl Actor<TestMsg> for TestActor {
    async fn receive (&mut self, msg: TestMsg, hself: &ActorHandle<TestMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: TestMsg as
            Cycle => {
                if self.round >= MAX_CYCLE {
                    let now = Instant::now();
                    let elapsed = now - self.start_time;
                    *ELASPED_ROUNDTRIP.lock().unwrap() += elapsed;
                    ReceiveAction::RequestTermination

                } else {
                    if self.round == 0 { 
                        self.start_time = Instant::now()
                    }
                    self.round += 1;
                    hself.try_send_msg(Cycle{}); // since this is the only send there can't be more than one message in the queue
                    ReceiveAction::Continue
                }
            }
        }
    }
}

//#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
#[tokio::main]
pub async fn main()->std::result::Result<(),Box<dyn std::error::Error>> {
    println!("-- running raw_bench with {} rounds", MAX_CYCLE);
    let start = Instant::now();

    let mut actor_system = ActorSystem::new("raw_msg");
    let a = actor_system.actor_of(TestActor { start_time: Instant::now(), round: 0 }, 8, "test_actor");

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