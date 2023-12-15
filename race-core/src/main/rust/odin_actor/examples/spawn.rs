#![allow(unused)]

use odin_actor::prelude::*;
use odin_actor::errors::Result;
//use odin_actor::tokio_channel::{ActorSystem,Actor,ActorHandle,Abortable, sleep};
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,AbortHandle,JoinHandle,sleep,spawn};


#[derive(Debug)] struct Spawn;
#[derive(Debug)] struct DataAvailable(i64);

define_actor_msg_type! { SpawnerMsg = Spawn | DataAvailable }

struct Spawner {
    count: usize,
    timer: Option<AbortHandle>,
    task: Option<JoinHandle<()>>
}
impl Spawner {
    fn new ()->Self { Spawner{ count: 0, timer: None, task: None } }

    async fn run_task (max_cycles: i64)->i64 {
        let mut n = 0;
        println!("      task running..");
        while n < max_cycles {
            n += 1;
            sleep(millis(1500)).await;
            println!("      task cycle {}", n);
        }
        println!("      task done.");
        n
    }
}

impl_actor! { match msg for Actor<Spawner,SpawnerMsg> as 
    _Start_ => cont! { 
        self.timer = Some(self.start_repeat_timer( 1, millis(1000)));
        println!("started timer");
    }
    _Timer_ => cont! { 
        self.count += 1;
        println!("tick {}", self.count);
        if self.count > 15 { 
            println!("spawner had enough, terminating..");
            self.request_termination(millis(500)).await; 
        }
    }
    Spawn => cont! {
        let hself = self.hself.clone();
        let max_cycles = 5;

        self.task = Some(
            spawn( async move {
                let result = Spawner::run_task(max_cycles).await;
                hself.send_msg( DataAvailable(result)).await;
            })
        )
    }
    DataAvailable => cont! {
        println!("got {:?}", msg)
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

    actor_system.start_all(millis(20)).await?;
    sleep(millis(2000)).await;
    spawner.send_msg(Spawn{}).await;

    actor_system.process_requests().await
}