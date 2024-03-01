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
use std::{sync::Arc, future::Future};
use anyhow::{anyhow,Result};

/* #region ChildActor ************************************************** */

#[derive(Debug)] pub struct ChildRequest { data: i64 }
define_actor_msg_type! { ChildMsg = ChildRequest }

struct Child<A: MsgReceiver<ChildResponse>> { 
    parent: A 
}

impl_actor! { match msg for Actor<Child<A>,ChildMsg> where A: MsgReceiver<ChildResponse> as 
    ChildRequest => cont! {
        sleep(secs(1)).await;
        let reply = if msg.data == 42 {"although I'm just a kid I know the answer"} else {"I don't know the answer, I'm just a kid"};
        self.parent.send_msg( ChildResponse(String::from(reply))).await.unwrap()
    }
}

/* #endregion ChildActor */

/* #region ParentActor ************************************************** */

#[derive(Debug)] pub struct ChildResponse(String);
define_actor_msg_type! { ParentMsg  = ChildResponse }

struct Parent {
    child_handle: Option<ActorHandle<ChildMsg>>
}

impl Parent {
    fn new ()->Self { Parent { child_handle: None } }
}

impl_actor! { match msg for Actor<Parent,ParentMsg> as 
    _Start_ => cont! { 
        if let Ok(child_handle) = spawn_dyn_actor!( self.hself, "child", Child{parent: self.hself.clone()}, 8).await {
            println!("created child actor: {:?}", child_handle);
            child_handle.send_msg(ChildRequest {data:42}).await.unwrap()
        }
    }
    ChildResponse => term! {
        println!("{} got answer: {:?}", self.id(), msg)
    }
}

/* #endregion ParentActor */

#[tokio::main]
async fn main() ->Result<()> {
    let mut actor_system = ActorSystem::new("main");

    let actor_handle = spawn_actor!( actor_system, "parent", Parent::new())?;
    
    actor_system.timeout_start_all(millis(20)).await?; // sends out _Start_ messages
    actor_system.process_requests().await?;

    Ok(())
}