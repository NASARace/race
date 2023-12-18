#![allow(unused)]

use odin_actor::prelude::*;
use odin_actor::tokio_kanal::{ActorSystem,Actor};

use anyhow::{anyhow,Result};

#[derive(Debug)]
pub struct Greet (&'static str);
//... define any other message struct our actor would process here
define_actor_msg_type! { GreeterMsg = Greet }

struct Greeter; // look ma - no fields

impl_actor! { match msg for Actor<Greeter,GreeterMsg> as
    Greet => cont! { println!("hello {}!", msg.0); }
}

pub struct Blah;

#[tokio::main]
async fn main() ->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let actor_handle = spawn_actor!( actor_system, "greeter", Greeter{})?;

    actor_handle.send_msg( Greet("world")).await?;
    actor_handle.send_msg( Greet("me")).await?;

    actor_system.terminate_and_wait( secs(5)).await?;

    Ok(())
}
