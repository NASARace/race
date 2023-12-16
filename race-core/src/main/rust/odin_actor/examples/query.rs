#![allow(unused)]

use odin_actor::prelude::*;
use odin_actor::errors::{OdinActorError,Result as OdinResult};
//use odin_actor::tokio_channel::{ActorSystem,Actor,ActorHandle,Abortable, sleep};
use odin_actor::tokio_kanal::{
    ActorSystem,ActorSystemHandle,Actor,ActorHandle,
    sleep,Query,query_ref,timeout_query_ref,
};

use std::{sync::Arc, future::Future, time::Duration};
use anyhow::{anyhow,Result};

/* #region messages ************************************************************/
#[derive(Debug)] struct Question(String);

#[derive(Debug)] struct Answer(String);
/* #endregion messages */

/* #region questioner ************************************************************/
#[derive(Debug)] struct AskNow;
define_actor_msg_type! { QuestionerMsg = AskNow }

struct Questioner <M> where M: MsgReceiver<Query<Question,Answer>> + Sync {
    responder: M
}

impl_actor! { match msg for Actor<Questioner<M>,QuestionerMsg> where M: MsgReceiver<Query<Question,Answer>> + Sync as
    AskNow => term! {
        let q = Question("what is the answer to life, the universe and everything?".to_string());
        /*
        match query_ref( self.responder, q).await {
            Ok(response) => println!("{} got the answer: {}", self.hself.id(), response.0),
            Err(e) => println!("{} : deepthought is gone {:?}", self.hself.id(), e)
        }
        */
    
        match timeout_query_ref( &self.responder, q, secs(1)).await {
            Ok(response) => println!("{} got the answer: {}", self.hself.id, response.0),
            Err(e) => match e {
                OdinActorError::ReceiverClosed => println!("{} : deepthought is gone.", self.hself.id),
                OdinActorError::TimeoutError(dur) => println!("{} : deepthought is still thinking after {:?}.", self.hself.id, dur),
                other => println!("{} : don't know what deepthought is doing", self.hself.id)
            }
        }
    }
}


/* #endregion */

/* #region responder ************************************************************/
define_actor_msg_type! { ResponderMsg = Query<Question,Answer> }

struct Responder;

impl_actor! { match msg for Actor<Responder,ResponderMsg> as
    Query<Question,Answer> => cont! {
        println!("{} got question: \"{:?}\", thinking..", self.hself.id(), msg.topic);
        sleep( millis(500)).await;
        //sleep( millis(1500)).await; // this will cause a timeout
        match msg.respond(Answer("42".to_string())).await {
            Ok(()) => {},
            Err(e) => println!("deepthought couldn't send the answer because {:?}", e)
        };
    }
}

/* #endregion answerer */

#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let deepthought = spawn_actor!( actor_system, "deepthought", Responder{})?;
    let mouse = spawn_actor!( actor_system, "mouse", Questioner {responder: deepthought})?;

    mouse.send_msg(AskNow{}).await;
    
    actor_system.process_requests().await?;

    Ok(())
}