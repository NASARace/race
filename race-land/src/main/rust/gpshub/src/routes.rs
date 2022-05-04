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
use warp::{self, Filter};

use crate::models::Gps;
use crate::ArcMxSrvOpts;
use crate::handlers;


fn with_server_opts(server_opts:ArcMxSrvOpts) -> impl Filter<Extract = (ArcMxSrvOpts,), Error = Infallible> + Clone {
  warp::any().map(move || server_opts.clone())
}

fn json_body() -> impl Filter<Extract = (Gps,), Error = warp::Rejection> + Clone {
  warp::body::content_length_limit(512).and(warp::body::json())
}

pub fn gps_route (server_opts: ArcMxSrvOpts) -> impl Filter<Extract = impl warp::Reply, Error = warp::Rejection> + Clone {
  warp::path("gps")
    .and( warp::put())
    .and( json_body())
    .and( with_server_opts(server_opts))
    .and_then( handlers::handle_gps)
}