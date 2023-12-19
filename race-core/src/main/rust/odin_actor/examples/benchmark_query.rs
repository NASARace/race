/*
 * Copyright (c) 2023, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
    responder: M,
    max_rounds: u64
}

impl_actor! { match msg for Actor<Requester<M>,RequesterMsg> where M: MsgReceiver<Query<Question,Answer>> + Send + Sync as
    StartQueries => term! {
        println!("--- running queries from other actor");
        run_queries(self.responder.clone(), self.max_rounds).await
    }
}

/* #endregion requester */

async fn run_queries <M> (responder: M, max_rounds: u64)->Result<()> where M: MsgReceiver<Query<Question,Answer>> + Sync {
    println!("running {} queries", max_rounds);
    let mut qb = QueryBuilder::<Answer>::new();
    let mut round: u64 = 0;
    let start_time = Instant::now();
    while round < max_rounds {
        let answer = qb.query_ref( &responder, Question{}).await?;
        if (answer.0 != 42) { panic!{"wrong answer on round {}: {}", round, answer.0} }

        round += 1;
    }
    let now = Instant::now();
    let elapsed = now - start_time;
    println!("{} query cycles in {} μs -> {} ns/ask-roundtrip", round, elapsed.as_micros(), elapsed.as_nanos() as u64 / round);

    Ok(())
}

#[tokio::main]
async fn main ()->Result<()> {
    let max_rounds = get_max_rounds();
    let mut actor_system = ActorSystem::new("main");

    let responder = spawn_actor!( actor_system, "responder", Responder{})?;
    let requester = spawn_actor!( actor_system, "requester", Requester { responder: responder.clone(), max_rounds })?;

    // no need for a second actor, we can ask from wherever we have an ActorHandle or MsgReceiver
    // BUT - running from tokio::main is 30x slower than from other task !!
    println!("--- running queries directly from main");
    run_queries( responder.clone(), max_rounds).await?;

    // run query from other non-actor task
    let jh = spawn( {
        println!("--- running queries from non-actor task");
        run_queries( responder, max_rounds)
    });
    jh.await?;

    // run query from another actor
    requester.send_msg(StartQueries{}).await;
    
    actor_system.process_requests().await;

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