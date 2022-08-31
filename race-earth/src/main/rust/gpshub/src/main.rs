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

mod models;
mod routes;
mod handlers;

use structopt::StructOpt;
use std::net::{SocketAddr, IpAddr, Ipv4Addr, Ipv6Addr};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use std::fs::File;
use std::cell::Cell;
use std::sync::Arc;
use tokio::sync::Mutex;
use warp::Filter;

/// command line options
#[derive(Clone,Debug,StructOpt)]
pub struct CliOpt {
    #[structopt(short,long)]
    pub in_host: String,

    #[structopt(long,default_value="20002")]
    pub in_port: u16,

    #[structopt(short,long,help=" [e.g. 224.0.0.222 for LAN]")]
    pub mc_ip: Option<String>, // e.g. 224.0.0.111

    #[structopt(long,default_value="20022")]
    pub mc_port: u16,

    #[structopt(short,long)]
    pub verbose: bool,

    #[structopt(short,long)]
    pub log_file: Option<String>,
}

/// what we pass into handlers to determine what to do with valid GPS packets received
pub struct SrvOpts {
    //--- multicast data
    pub mc_addr: Option<SockAddr>,
    pub mc_sock: Option<Socket>,
    
    pub verbose: bool,
  
    //--- log file
    pub log_file: Option<Cell<File>>,
}
  
pub type ArcMxSrvOpts = Arc<Mutex<SrvOpts>>;

#[tokio::main]
async fn main() {
    let cli_opts = CliOpt::from_args();
    let srv = create_server_opts(&cli_opts);

    if let Some(socket_addr) = get_gps_addr(&cli_opts) {
        println!("listening on {:?}/gps", socket_addr);
        if srv.mc_addr.is_some() { println!("multicasting to {}:{}", cli_opts.mc_ip.unwrap(), cli_opts.mc_port) }
        if let Some(ref log_file) = cli_opts.log_file { println!("writing to log file: {}", log_file) }
        println!("terminate with Ctrl-C ..");

        let aso = Arc::new(Mutex::new(srv));
        if cli_opts.verbose {
            println!("verbose logging enabled");
            let log = warp::log::custom(|info| {  println!("{} {} -> {}", info.method(), info.path(), info.status()) });
            warp::serve( routes::gps_route(aso).with(log) ).run(socket_addr).await
          } else {
            warp::serve( routes::gps_route(aso)).run(socket_addr).await
          }
    }
}

fn create_server_opts( cli_opts: &CliOpt) -> SrvOpts {
    let sock_addr = get_mc_addr(cli_opts);

    let mc_addr = sock_addr.map( |addr| SockAddr::from(addr));
    let mc_sock = sock_addr.and_then( |ref addr| get_mc_socket(addr));

    let log_file = cli_opts.log_file.as_ref().and_then( |ref path| get_log_file(path));

    SrvOpts {
        mc_addr: mc_addr,
        mc_sock: mc_sock,
        verbose: cli_opts.verbose,
        log_file: log_file,
    }
}

fn get_mc_addr(cli_opt: &CliOpt) -> Option<SocketAddr> {
    let ip_addr: Option<IpAddr> = cli_opt.mc_ip.as_ref().and_then( |ref ip_spec| match ip_spec.parse::<IpAddr>() {
        Ok(addr) => {
          if addr.is_multicast() {
             Some(addr)
          } else {
             eprintln!("not a multicast address: {}", ip_spec);
             None
          }
        },
        Err(e) => {
            eprintln!("invalid multicast address: {}", e); 
            None 
        }
    });


    let port: u16 = cli_opt.mc_port;

    ip_addr.map( |addr| SocketAddr::new(addr,port))
}

fn get_mc_socket(socket_addr: &SocketAddr) -> Option<Socket> {
    let domain = if socket_addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP)).ok()?;
  
    //socket.set_read_timeout(Some(Duration::from_millis(100)))?;
    socket.set_reuse_port(true).ok()?;
  
    if socket_addr.is_ipv4() {
        socket.bind(&SockAddr::from(SocketAddr::new( Ipv4Addr::new(0, 0, 0, 0).into(), 0,))).ok()?;
    } else {
        socket.bind(&SockAddr::from(SocketAddr::new( Ipv6Addr::new(0, 0, 0, 0, 0, 0, 0, 0).into(), 0,))).ok()?;
    }
  
    Some(socket)
}
  
fn get_log_file (path: &String) -> Option<Cell<File>> {
    match File::create(path) {
        Ok(file) => Some(Cell::new(file)),
        Err(e) => {
            eprintln!("failed to open log file: {}", e);
            None
        }
    }
}

fn get_gps_addr (cli_opt: &CliOpt)-> Option<SocketAddr> {
    let in_addr = format!("{}:{}", &cli_opt.in_host, cli_opt.in_port);
    match in_addr.parse::<SocketAddr>() {
        Ok(socket_addr) => Some(socket_addr),
        Err(e) => { eprintln!("invalid server address: {}", e); None }
    }
}