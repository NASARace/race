#![allow(unused)]

use odin_actor::prelude::*;
use odin_actor::errors::Result;
//use odin_actor::tokio_channel::{ActorSystem,Actor,ActorHandle,Abortable, sleep};
use odin_actor::tokio_kanal::{
    ActorSystem,ActorSystemHandle,Actor,ActorHandle,AbortHandle,JoinHandle,
    sleep, timeout, spawn_blocking, block_on, block_on_send_msg, block_on_timeout_send_msg
};

use std::thread;

#[derive(Debug)] struct Spawn;
#[derive(Debug,Clone)] struct DataAvailable(i64);
define_actor_msg_set!( enum SpawnerMsg {Spawn,DataAvailable});

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

impl Actor<SpawnerMsg> for Spawner {
    async fn receive (&mut self, msg: SpawnerMsg, hself: &ActorHandle<SpawnerMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: SpawnerMsg as
            _Start_ => cont! { 
                self.timer = Some(hself.start_repeat_timer( 1, millis(1000)));
                println!("started timer");
            }
            _Timer_ => cont! { 
                self.count += 1;
                println!("tick {}", self.count);
                if self.count > 15 { 
                    println!("spawner had enough, terminating..");
                    hsys.request_termination(millis(500)).await; 
                }
            }
            Spawn => cont! {
                let hself = hself.clone();
                let max_cycles = 5;

                self.task = Some(
                    spawn_blocking( move || {
                        let result = Self::run_task(max_cycles);

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
                println!("{} actor got {:?}", hself.id, msg)
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
                println!("{} terminated", hself.id);
            }
        }
    }
}


#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let spawner = actor_system.actor_of(Spawner::new(), 8, "spawner");

    actor_system.start_all(millis(20)).await?;
    sleep(millis(2000)).await;
    spawner.send_msg(Spawn{}).await;

    actor_system.process_requests().await
}