/*
 * Copyright (c) 2024, United States Government, as represented by the
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

use std::sync::{Arc,Mutex};
use odin_job::{JobScheduler,JobHandle,secs};
use anyhow::{Result,anyhow};
use tokio::{self,time::sleep};

/// basic example of how to use odin_job based FnMut scheduling

#[tokio::main]
async fn main()->Result<()> {
    let mut trace = Arc::new(Mutex::new(Vec::<usize>::new()));

    let mut scheduler = JobScheduler::new();
    scheduler.run()?;

    // Note odin_macro::fn_mut!(..) provides some syntatic sugar for specifying the closures with respective captures 
    scheduler.schedule_once( secs(4), { let t=trace.clone();  move |_ctx| record(&t, 1) })?;
    scheduler.schedule_once( secs(6), { let t=trace.clone(); move |_ctx| record(&t, 2) })?;
    let job_handle = scheduler.schedule_repeated( secs(2), secs(3), { let t=trace.clone(); move |_ctx| record(&t, 3)})?;
    scheduler.schedule_once( secs(2), { let t=trace.clone(); move |_ctx| record(&t, 4)})?;

    sleep( secs(15)).await;
    println!("now cancelling {job_handle:?}");
    scheduler.abort_job(job_handle);

    println!("immediate-scheduling self-canceling 1-sec repeat job..");
    scheduler.schedule_repeated(secs(0), secs(1), {
        let mut n_remaining=3;
        move |ctx| {
            println!(". repeat job #{} n_remaining={}", ctx.current_id(), n_remaining);
            n_remaining -= 1;
            if n_remaining == 0 {
                println!(". repeat job #{} now cancelling itself", ctx.current_id());
                ctx.cancel_repeat();
            }
        }    
    });

    sleep( secs(4)).await;
    println!("shutting down scheduler");
    scheduler.abort();

    if let Ok(trace) = trace.lock() {
        let v = trace.as_ref();
        println!("trace: {:?}", v);
        assert_eq!( v, vec!(3,4,1,3,2,3,3,3));
    }

    Ok(())
}

fn record(trace: &Arc<Mutex<Vec<usize>>>, x: usize) {
    println!("Hola! {x}");
    trace.lock().unwrap().push(x);
}

