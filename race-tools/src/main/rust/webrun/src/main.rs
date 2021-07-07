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

#[macro_use]
extern crate lazy_static;

use warp::Filter;
use bytes::Bytes;
use std::process::Command;
use structopt::StructOpt;


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

const CLEAR_SCREEN: &str = "\u{001b}[2J\u{001b}[0;0H";
const RESET_STYLES: &str = "\u{001b}[0m";
const REVERSED: &str = "\u{001b}[7m";
//const RED: &str = "\u{001b}[31m";

#[derive(Clone,Debug,StructOpt)]
struct Opt {
    #[structopt(long,default_value="4000")]
    base_port: u16,

    #[structopt(short,long,default_value="1")]
    console: u16,
    
    #[structopt(short,long)]
    verbose: bool
}

lazy_static! {
    static ref OPT: Opt = Opt::from_args();
    static ref PORT: u16 = OPT.base_port + OPT.console;
}

static HOST: &str = "localhost";
static ORIGIN: &str = "http://localhost:8080";  // NOTE - this means we have to load the page through servedoc
static PATH: &str = "run";

#[tokio::main]
async fn main() {
  let cors = warp::cors()
    //.allow_any_origin() // this would allow us to run slides from the file system but leaves webrun wide open
    .allow_origin( ORIGIN)  // note this means we have to run slides through servedoc
    .allow_headers(vec!["content-type"])
    .allow_methods(vec!["POST"]);

  print_prompt();

  // POST /run {body: "<prog> <arg>.."}
  let run_request = warp::post()
    .and( warp::path(PATH))
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

      print_prompt();
      "done" // what we return to the client
    });

  warp::serve(run_request.with(cors))
      .run(([127, 0, 0, 1], *PORT))
      .await;
}

fn print_prompt () {
  print!("{}", CLEAR_SCREEN);
  if OPT.verbose {
    println!("{}{}console {} - waiting for POST command from {} on http://{}:{}/{} (terminate with ctrl-C)..{}", 
              RESET_STYLES,REVERSED, OPT.console, ORIGIN, HOST, *PORT, PATH, RESET_STYLES);
    
  } else {
    println!("{}{}console {} - waiting for command..{}", RESET_STYLES,REVERSED, OPT.console, RESET_STYLES);
  }
}