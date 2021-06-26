/*
 * Copyright (c) 2021, United States Government, as represented by the
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


use warp::Filter;
use bytes::Bytes;
use std::process::Command;
use std::env;

/// webrun - a simple http server that responds to http://localhost:303x/run POST requests with a body that
/// contains a OS command to run from a console. Start in a console with
///       > webrun <console-no> [--base <port>]
/// Terminate with ctrl-C
///
/// Used by slide presentations that run demos by including
///       <kbd>console x: prog arg...</kbd>
/// elements. Double clicking on those will send the respective POST to a already running webrun server.
///
/// This is native since we don't want to spawn another JVM for something that might not involve Java

//const CLEAR_SCREEN: &str = "\u{001b}[2J";
const RESET_STYLES: &str = "\u{001b}[0m";
const REVERSED: &str = "\u{001b}[7m";
//const RED: &str = "\u{001b}[31m";

const BASE_PORT: u16 = 4000;

#[tokio::main]
async fn main() {
  
  let console_n: u16 = env::args().nth(1).map_or_else(|| 0, |s| s.parse().unwrap());
  let base_port: u16 = env::args().nth(2).map_or_else(|| BASE_PORT, |o| {
    if o == "--base" {
      println!(" using base port {:?}", env::args().nth(3));
      env::args().nth(3).map_or_else(|| BASE_PORT, |s| s.parse().unwrap())
    } else { BASE_PORT }
  });
  let port: u16 = base_port + console_n;

  print_prompt(console_n);

  // POST /run {body: "<prog> <arg>.."}
  let run_request = warp::post()
    .and( warp::path("run"))
    .and( warp::body::bytes())
    .map( move |bytes: Bytes| {
      let content = std::str::from_utf8(&bytes[..]).unwrap();

      println!("> {}", content);

      let mut parts = content.split(" ");
      let prog = parts.next().unwrap();
      let args: Vec<&str> = parts.collect();

      let _status = Command::new(prog)
        .args(args)
        .status()
        .expect("command failed to start");

      print_prompt(console_n);
      "done" // what we return to the client
    });

  let cors = warp::cors()
    //.allow_any_origin() // this would allow us to run slides from the file system but leaves webrun wide open
    .allow_origin("http://localhost:8080")  // note this means we have to run slides through servedoc
    .allow_headers(vec!["content-type"])
    .allow_methods(vec!["POST"]);

  warp::serve(run_request.with(cors))
      .run(([127, 0, 0, 1], port))
      .await;
}

fn print_prompt (console_n: u16) {
  //print!(CLEAR_SCREEN);
  println!("\n{}{}console {} - waiting for command{}", RESET_STYLES,REVERSED, console_n, RESET_STYLES);
}