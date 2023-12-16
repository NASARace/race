#![allow(unused)]

use odin_actor::prelude::*;
//use odin_actor::tokio_channel::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,QueryBuilder, Query,spawn};
use odin_actor::tokio_kanal::{ActorSystem,ActorSystemHandle,Actor,ActorHandle,QueryBuilder, Query,spawn};

use std::time::{Instant,Duration};
use anyhow::{anyhow,Result};

#[derive(Debug)] struct Question;
#[derive(Debug)] struct Answer(i64);

/* #region responder ***********************************************************************************/

define_actor_msg_type! { ResponderMsg = Query<Question,Answer> }

struct Responder;

impl_actor! { match msg for Actor<Responder,ResponderMsg> as
    Query<Question,Answer> => cont! {
        match msg.respond(Answer(42)).await {
            Ok(()) => {},
            Err(e) => panic!("deepthought couldn't send the answer because {:?}", e)
        };
    }
}

/* #endregion responder */

/* #region requester *************************************************************************************/

#[derive(Debug)] struct StartQueries;

define_actor_msg_type! { RequesterMsg = StartQueries }

struct Requester <M> where M: MsgReceiver<Query<Question,Answer>> {
    responder: M
}

impl_actor! { match msg for Actor<Requester<M>,RequesterMsg> where M: MsgReceiver<Query<Question,Answer>> + Send + Sync as
    StartQueries => term! {
        println!("--- running queries from other actor");
        run_queries(self.responder.clone()).await
    }
}

/* #endregion requester */

async fn run_queries <M> (responder: M)->Result<()> where M: MsgReceiver<Query<Question,Answer>> + Sync {
    println!("running {} queries", MAX_ROUNDS);
    let mut qb = QueryBuilder::<Answer>::new();
    let mut round: u64 = 0;
    let start_time = Instant::now();
    while round < MAX_ROUNDS {
        let answer = qb.query_ref( &responder, Question{}).await?;
        if (answer.0 != 42) { panic!{"wrong answer on round {}: {}", round, answer.0} }

        round += 1;
    }
    let now = Instant::now();
    let elapsed = now - start_time;
    println!("{} query cycles in {} μs -> {} ns/ask-roundtrip", round, elapsed.as_micros(), elapsed.as_nanos() as u64 / round);

    Ok(())
}

// larger numbers -> less per round (L2?)
const MAX_ROUNDS: u64 = 1_000_000;
//const MAX_ROUNDS: u64 = 100;

#[tokio::main]
async fn main ()->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let responder = spawn_actor!( actor_system, "responder", Responder{})?;
    let requester = spawn_actor!( actor_system, "requester", Requester { responder: responder.clone() })?;

    // no need for a second actor, we can ask from wherever we have an ActorHandle or MsgReceiver
    // BUT - running from tokio::main is 30x slower than from other task !!
    println!("--- running queries directly from main");
    run_queries( responder.clone()).await?;

    // no difference between actor and non-actor task

    let jh = spawn( {
        println!("--- running queries from non-actor task");
        run_queries( responder)
    });
    jh.await?;

    requester.send_msg(StartQueries{}).await;
    
    actor_system.process_requests().await;

    Ok(())
}