#![allow(unused)]

use tokio;
use std::time::{Instant,Duration};
use odin_actor::prelude::*;
use odin_actor::errors::Result;

mod provider {
    use odin_actor::prelude::*;
    use odin_actor::tokio_kanal::{Actor, ActorHandle};

    #[derive(Debug)] pub struct TriggerCb{}
    #[derive(Debug)] pub struct AddCb(pub Callback<u64>);

    define_actor_msg_type!{ pub ProviderMsg = AddCb | TriggerCb }

    pub struct Provider {
        data: u64,
        callbacks: CallbackList<u64>,
    }
    impl Provider {
        pub fn new()->Self { Provider{ data: 0, callbacks: CallbackList::new() } }
    }

    impl_actor! { match msg for Actor<Provider,ProviderMsg> as
        AddCb => cont! { 
            self.callbacks.push( msg.0) 
        }
        TriggerCb => cont! { 
            self.data += 1;
            self.callbacks.execute(&self.data).await 
        }
    }
}

mod client {
    use std::time::{Instant,Duration};
    use odin_actor::prelude::*;
    use odin_actor::tokio_kanal::{Actor, ActorHandle};
    use crate::provider::{ProviderMsg,AddCb,TriggerCb};

    #[derive(Debug)] pub struct Update(u64);
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
            //let cb = Callback::from( try_send_msg_callback!( &self.hself, |v:&u64| Update(*v) ));
            let cb = Callback::from( send_msg_callback!( &self.hself, |v:&u64| Update(*v) ));

            self.provider.send_msg( AddCb(cb)).await;
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
                self.provider.try_send_msg( TriggerCb{});
            }
        }
        Update => {
            if msg.0 < self.max_rounds { 
                self.provider.try_send_msg( TriggerCb{});
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

#[tokio::main]
async fn main()->Result<()> {
    let max_rounds = get_max_rounds();
    println!("-- running benchmark_cb with {} rounds", max_rounds);

    let mut actor_system = ActorSystem::new("benchmark_cb");
    let prov = spawn_actor!( actor_system, "provider", provider::Provider::new())?;
    let cli = spawn_actor!( actor_system, "client", client::Client::new(max_rounds, prov))?;

    actor_system.timeout_start_all(millis(20)).await?;
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