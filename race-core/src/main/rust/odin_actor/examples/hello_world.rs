#![allow(unused)]

use odin_actor::prelude::*;
//use odin_actor::tokio_channel::{ActorSystem,Actor,ActorHandle};
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle};

use anyhow::{anyhow,Result};

#[derive(Debug)]
pub struct Greet { whom: &'static str }
//... define any other message struct our actor would process here
define_actor_msg_set!( pub enum GreeterMsg {Greet});

struct Greeter; // look ma - no fields

impl Actor<GreeterMsg> for Greeter {
    async fn receive (&mut self, msg: GreeterMsg, hself: &ActorHandle<GreeterMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: GreeterMsg as
            Greet => cont! { println!("hello {}!", msg.whom); }
        }
    }
}

pub struct Blah;

#[tokio::main]
async fn main() ->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let actor_handle = actor_system.actor_of( Greeter{}, 8, "greeter");

    actor_handle.send_msg( Greet{whom:"world"}).await?;
    actor_handle.send_msg( Greet{whom:"me"}).await?;

    actor_system.terminate_and_wait( secs(5)).await?;

    Ok(())
}
