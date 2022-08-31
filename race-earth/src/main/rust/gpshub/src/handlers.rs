/*
 * Copyright (c) 2022, United States Government, as represented by the
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

use std::convert::Infallible;
use warp::{self, http::StatusCode};
use std::io::Write;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::ArcMxSrvOpts;
use crate::models::Gps;


pub async fn handle_gps (gps: Gps, server_opts: ArcMxSrvOpts) -> Result<impl warp::Reply, Infallible> {

  let epoch_recv = get_epoch_msec();
  let mut srv = server_opts.lock().await;
  let rec = gps.to_recv_csv(epoch_recv);

  if srv.verbose {
    println!("{}", &gps)
  }

  if let Some(ref mut cr) = srv.log_file.as_mut() {
    //writeln!(cr.get_mut(), "{}", &gps).unwrap_or_else(|e| eprintln!("error writing log_file: {}", e))
    writeln!(cr.get_mut(), "{}", &rec).unwrap_or_else(|e| eprintln!("error writing log_file: {}", e))
  }

  if let Some(ref mc_sock) = &srv.mc_sock {
    if let Some(ref mc_addr) = &srv.mc_addr {
      match mc_sock.send_to(rec.as_bytes(), mc_addr) {
        Ok(len) => if srv.verbose { println!("sent {} bytes to {:?}", len, mc_addr) },
        Err(err) => eprintln!("error sending record to {:?}: {}", mc_addr, err)
      }
    }
  }

  Ok(StatusCode::OK)  
}

fn get_epoch_msec() -> u64 {
  SystemTime::now().duration_since(UNIX_EPOCH).expect("Time went backwards").as_millis().try_into().unwrap()
}