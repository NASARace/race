#![allow(unused)]

use tokio;
use std::time::{Instant,Duration};
use odin_actor::prelude::*;
use odin_actor::errors::{OdinActorError, Result};
use odin_actor::tokio_kanal::{Actor, ActorHandle, ActorSystem, PreActorHandle};

mod provider {
    use odin_actor::{prelude::*, ActorActionList};
    use odin_actor::tokio_kanal::{Actor, ActorHandle};

    #[derive(Debug)] pub struct ExecuteActions{}

    define_actor_msg_type!{ pub ProviderMsg = ExecuteActions }

    pub struct Provider<A> where A: ActorActionList<u64> {
        data: u64,
        actions: A,
    }
    impl<A> Provider<A> where A: ActorActionList<u64> {
        pub fn new(actions: A)->Self { Provider{ data: 0, actions } }
    }

    impl_actor! { match msg for Actor<Provider<A>,ProviderMsg> where A: ActorActionList<u64> as
        ExecuteActions => cont! { 
            self.data += 1;
            self.actions.execute(&self.data).await 
        }
    }
}

mod client {
    use std::time::{Instant,Duration};
    use odin_actor::prelude::*;
    use odin_actor::tokio_kanal::{Actor, ActorHandle};
    use crate::provider::{ProviderMsg,ExecuteActions};

    #[derive(Debug)] pub struct Update(pub u64);
    #[derive(Debug)] pub struct PingSelf(u64);
    #[derive(Debug)] pub struct TryPingSelf(u64);

    define_actor_msg_type!{ pub ClientMsg = PingSelf | TryPingSelf | Update }

    pub struct Client {
        max_rounds: u64,
        provider: ActorHandle<ProviderMsg>,
        start_time: Instant,
        elapsed_ping: Duration,
        elapsed_try_ping: Duration,
    }
    impl Client {
        pub fn new (max_rounds: u64, provider: ActorHandle<ProviderMsg>)->Self {
            Client{ max_rounds, provider, start_time: Instant::now(), elapsed_ping: Duration::new(0,0), elapsed_try_ping: Duration::new(0,0) }
        }
    }

    impl_actor! { match msg for Actor<Client,ClientMsg> as
        _Start_ => cont! {
            self.start_time = Instant::now();
            self.hself.try_send_msg( TryPingSelf(0));
        }
        TryPingSelf => cont! {
            // measure sync msg send time
            if msg.0 < self.max_rounds {
                self.hself.try_send_msg( TryPingSelf(msg.0 + 1));
            } else {
                self.elapsed_try_ping = Instant::now() - self.start_time;
                println!("time per self try_send_msg roundtrip: {} ns", self.elapsed_try_ping.as_nanos() as u64 / self.max_rounds);

                self.start_time = Instant::now();
                self.hself.send_msg( PingSelf(0)).await;
            }
        }
        PingSelf => cont! {
            // measure async msg send time
            if msg.0 < self.max_rounds {
                self.hself.send_msg( PingSelf(msg.0 + 1)).await;
            } else {
                self.elapsed_ping = Instant::now() - self.start_time;
                println!("time per self send_msg roundtrip: {} ns", self.elapsed_ping.as_nanos() as u64 / self.max_rounds);

                // done measuring raw msg roundtrip, now start callback loop
                self.start_time = Instant::now();
                self.provider.try_send_msg( ExecuteActions{});
            }
        }
        Update => {
            if msg.0 < self.max_rounds { 
                self.provider.try_send_msg( ExecuteActions{} );
                ReceiveAction::Continue 
            } else {
                let elapsed = Instant::now() - self.start_time;
                println!("{} callback roundtrips in {} μs -> {} ns/callback", 
                        self.max_rounds, elapsed.as_micros(), (elapsed.as_nanos() as u64 / self.max_rounds));
                println!("callback overhead per roundtrip: {} ns", 
                    (elapsed.as_nanos() - self.elapsed_try_ping.as_nanos() - self.elapsed_ping.as_nanos()) as u64/self.max_rounds);
                ReceiveAction::RequestTermination 
            }
        }
    }
}

//#[tokio::main(flavor = "multi_thread", worker_threads = 3)]
//#[tokio::main(flavor = "current_thread")]
#[tokio::main]
async fn main()->Result<()> {
    let max_rounds = get_max_rounds();
    println!("-- running benchmark_alist with {} rounds", max_rounds);

    let mut actor_system = ActorSystem::new("benchmark_cb");

    let pre_prov = PreActorHandle::new("provider",8);
    let cli = spawn_actor!( actor_system, "client", client::Client::new(max_rounds, ActorHandle::from(&pre_prov)))?;

    define_actor_action_list!{ for actor_handle in ProviderActions (data: &u64):
        client::ClientMsg => actor_handle.try_send_msg( client::Update(*data))
    }
    let prov = spawn_pre_actor!( actor_system, pre_prov, provider::Provider::new( ProviderActions(cli)))?;


    actor_system.start_all(millis(20)).await?;
    actor_system.process_requests().await?;

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