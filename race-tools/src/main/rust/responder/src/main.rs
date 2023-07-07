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

#[macro_use]
extern crate lazy_static;

use std::{
    collections::HashMap,
    thread,
    time,
};
use warp::{
    http,
//    path,
    Filter,
//    Rejection
};
use structopt::StructOpt;
//use thiserror;
//use bytes::Bytes;

#[derive(Clone,Debug,StructOpt)]
struct Opt {
    #[structopt(long,default_value="8080")]
    port: u16,

    #[structopt(short,long)]
    verbose: bool,

    #[structopt(long,default_value="test")]
    root: String,
}

lazy_static! {
    static ref OPT: Opt = Opt::from_args();
}


fn get_delay_secs(map: &HashMap<String,String>) -> u64 {
    match map.get("delay") {
        Some(delay_spec) => delay_spec.parse::<u64>().unwrap(),
        None => 0
    }
}

fn get_response_status(map: &HashMap<String,String>) -> u16 {
    match map.get("status") {
        Some(status_spec) => status_spec.parse::<u16>().unwrap(),
        None => 200 // OK
    }
}

fn get_query_string(map: &HashMap<String,String>) -> String {
    if map.is_empty() {
        String::new()
    } else {
        let mut qs = String::from("?");
        for (key, value) in map.into_iter() {
            if qs.len() > 1 {
                qs.push('&');
            }
            qs.push_str(key);
            qs.push('=');
            qs.push_str(value);
        }
        qs
    }
}

/*
pub fn http_request() -> impl Filter<Extract = (http::Request<Bytes>,), Error = Rejection> + Copy {
    // TODO: extract `hyper::Request` instead
    // blocked by https://github.com/seanmonstar/warp/issues/139
    warp::any()
        .and(warp::method())
        .and(warp::filters::path::full())
        .and(warp::filters::query::raw())
        .and(warp::header::headers_cloned())
        .and(warp::body::bytes())
        .and_then(|method, path: path::FullPath, query, headers, bytes| async move {
            let uri = http::uri::Builder::new()
                .path_and_query(format!("{}?{}", path.as_str(), query))
                .build()
                .map_err(Error::from)?;

            let mut request = http::Request::builder()
                .method(method)
                .uri(uri)
                .body(bytes)
                .map_err(Error::from)?;

            *request.headers_mut() = headers;

            Ok::<http::Request<Bytes>, Rejection>(request)
        })
}

#[derive(thiserror::Error, Debug)]
pub enum Error {
    #[error(transparent)]
    Http(#[from] http::Error),
}
*/

/// simple test server that only accepts a given request path ('/test' as default) and uses (optional)
/// query parameters to delay response ('delay=<sec>') and explicitly set response status ('status=<num>', default 200)
/// example request URI: localhost:8080/test?delay=4&status=201
#[tokio::main]
async fn main() {
    let route = warp::path(OPT.root.as_str())
    .and(warp::query::<HashMap<String, String>>())
    .map(|map: HashMap<String, String>| {
        let delay_secs = get_delay_secs(&map);
        let status = get_response_status(&map);
        let query = get_query_string(&map);

        if OPT.verbose {
            println!("got request for {}{}\n", OPT.root.as_str(), query);
        }

        if delay_secs > 0 {
            thread::sleep(time::Duration::from_secs(delay_secs));
        }

        let mut response = String::from("/");
        response.push_str(OPT.root.as_str());
        response.push_str(query.as_str());
        response.push_str(" -> ");
        response.push_str( &status.to_string());
        //response.push('\n');

        http::Response::builder()
        .status(status)
        .body(response)
    });

    let log = warp::log::custom(|info| {
        println!("{} request from {} for \"{}\" -> {} after {} msec", 
            info.method(), info.remote_addr().unwrap(), info.path(), info.status(), info.elapsed().as_millis() )
    });

    println!("response server running on http://localhost:{}/{}", OPT.port, OPT.root);
    println!("(terminate with ctrl-C)");

    if OPT.verbose {
        warp::serve( route.with(log) ).run(([127, 0, 0, 1], OPT.port)).await
    } else {
        warp::serve( route ).run(([127, 0, 0, 1], OPT.port)).await
    }
}
