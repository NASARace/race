#![allow(unused)]

use odin_actor::prelude::*;
use odin_config::load_config;
//use odin_actor::tokio_channel::{ActorSystem,Actor,ActorHandle,Abortable, sleep};
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,AbortHandle,sleep};

use std::{time::Duration,default::Default};
use anyhow::{anyhow,Result};
use ron::de;
use serde::Deserialize;

#[derive(Deserialize)]
struct TickerConfig {
    interval_sec: u64,
}
impl Default for TickerConfig {
    fn default()->Self { TickerConfig { interval_sec: 1 } }
}


struct Ticker {
    config: TickerConfig,

    count: u64,
    timer: Option<AbortHandle>
}
impl Ticker {
    fn new (config: TickerConfig)->Self { 
        Ticker { config, count: 0, timer: None }
    }
}

impl Actor<SysMsg> for Ticker {
    async fn receive (&mut self, msg: SysMsg, hself: &ActorHandle<SysMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: SysMsg as
            _Start_ => cont! { 
                self.timer = Some(hself.start_repeat_timer( 1, secs(self.config.interval_sec)));
                println!("started timer");
            }
            _Timer_ => cont! { 
                self.count += 1;
                println!("tick {}", self.count);
            }
            _Terminate_ => stop! {
                if let Some(timer) = &self.timer { 
                    timer.abort();
                    self.timer = None;
                }
                println!("terminated timer");
            }
        }
    }
}

#[tokio::main]
async fn main() ->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    // note this assumes the app is running in the parent workspace dir
    let ticker_config = load_config("odin_actor/examples/config/ticker.ron").expect("no valid config for Ticker actor");
    let ticker_handle = actor_system.actor_of( Ticker::new( ticker_config), 8, "ticker");

    actor_system.start_all(millis(20)).await?; // sends out _Start_ messages
    sleep( secs(5)).await;
    actor_system.terminate_and_wait( millis(20)).await?;  // sends out _Terminate_ messages and waits for actor completion

    Ok(())
}