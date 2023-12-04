#![allow(unused)]

use odin_actor::prelude::*;
use odin_actor::errors::{OdinActorError,Result as OdinResult};
//use odin_actor::tokio_channel::{ActorSystem,Actor,ActorHandle,Abortable, sleep};
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,OneshotSender,sleep,Respondable,timeout_ask};
use std::{sync::Arc, future::Future, time::Duration};
use anyhow::{anyhow,Result};

/* #region messages ************************************************************/
#[derive(Debug)] struct Question {
    q: String,
    reply_to: OneshotSender<Answer>
}
impl Respondable<Answer> for Question {
    fn sender (self)->OneshotSender<Answer> { self.reply_to }
}

#[derive(Debug)] struct Answer(String);
/* #endregion messages */

/* #region questioner ************************************************************/
#[derive(Debug)] struct Ask;
define_actor_msg_set!( enum QuestionerMsg { Ask } );

struct Questioner <A:MsgReceiver<Question>> {
    responder: A
}

impl<A> Actor<QuestionerMsg> for Questioner<A> where A: MsgReceiver<Question> + Send + Clone {
    async fn receive (&mut self, msg: QuestionerMsg, hself: &ActorHandle<QuestionerMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: QuestionerMsg as
            Ask => request_termination! {
                let q = String::from("what is the answer to life, the universe and everything?");
                match timeout_ask( secs(1), self.responder.clone(), |reply_to| Question {q, reply_to}).await {
                    Ok(response) => println!("{} got the answer: {}", hself.id, response.0),
                    Err(e) => match e {
                        OdinActorError::ReceiverClosed => println!("{} : deepthought is gone.", hself.id),
                        OdinActorError::TimeoutError(dur) => println!("{} : deepthought is still thinking after {:?}.", hself.id, dur),
                        other => println!("{} : don't know what deepthought is doing", hself.id)
                    }
                }
            }
        }
    }
}

/* #endregion */

/* #region responder ************************************************************/
define_actor_msg_set!( enum ResponderMsg { Question });

struct Responder;

impl Actor<ResponderMsg> for Responder {
    async fn receive (&mut self, msg: ResponderMsg, hself: &ActorHandle<ResponderMsg>, hsys: &ActorSystemHandle)->ReceiveAction {
        match_actor_msg! { msg: ResponderMsg as
            Question => cont! {
                println!("{} got question: \"{}\", thinking..", hself.id, msg.q);
                sleep( millis(500)).await;
                match msg.reply(Answer(String::from("42"))).await {
                    Ok(()) => {},
                    Err(e) => println!("deepthought couldn't send the answer because {:?}", e)
                };
            }
        }
    }
}

/* #endregion answerer */

#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let deepthought = actor_system.actor_of( Responder{}, 8, "deepthought");
    let mouse = actor_system.actor_of( Questioner {responder: deepthought}, 8, "mouse");

    mouse.send_msg(Ask{}).await;
    
    actor_system.process_requests().await?;

    Ok(())
}