#![allow(unused)]

use odin_actor::prelude::*;
use odin_actor::tokio_kanal::{ActorSystem,Actor};
use std::fmt::Debug;
use anyhow::{anyhow,Result};

#[derive(Debug)] struct Greet (&'static str);

//... define any other message struct our actor would process here
define_actor_msg_type! { GreeterMsg = Greet }

struct Greeter; // look ma - no fields


impl_actor! { match msg for Actor<Greeter,GreeterMsg> as
    Greet => cont! { 
        println!("hello {}!", msg.0); 

        if msg.0 != "me" {
            let myself = self.hself.clone();
            self.try_send_msg( _Exec_( Box::new(move|| { myself.try_send_msg(Greet("me")); }) ));
        }
    }
}

pub struct Blah;

#[tokio::main]
async fn main() ->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let actor_handle = spawn_actor!( actor_system, "greeter", Greeter{})?;

    actor_handle.send_msg( Greet("world")).await?;

    actor_system.process_requests().await?;

    Ok(())
}