#![allow(unused)]

#[macro_use]
extern crate lazy_static;

use std::default::Default;
use std::error::Error;
use std::net::{IpAddr, SocketAddr};

use axum::{
    extract::MatchedPath,
    extract::Query,
    http::Request,
    response::Html,
    Router,
    routing::get
};
use serde_derive::Deserialize;
use structopt::StructOpt;
use tokio::net::TcpListener;
use tower_http::{
    classify::{ServerErrorsAsFailures, SharedClassifier},
    trace::TraceLayer,
};
use tracing::{info_span, Level, Span};
use tracing_subscriber::{filter, layer::SubscriberExt, util::SubscriberInitExt};
use odin_common::geo::BoundingBox;

use odin_common::strings::deserialize_arr4;
use odin_dem::*;

#[derive(StructOpt)]
struct CliOpts {
    #[structopt(long,default_value="8081")]
    port: u16,

    #[structopt(long,default_value="127.0.0.1")]
    ip_addr: IpAddr,

    #[structopt(short,long)]
    verbose: bool
}

// the "default_xx" paths are a serde quirk - no default_value, only functions allowed
#[derive(Deserialize,Debug)]
struct GetMapQuery {
    #[serde(default = "default_service")]
    service: String,

    #[serde(default = "default_version")]
    version: String,

    #[serde(default)]
    layers: Option<String>,

    #[serde(default)]
    styles: Option<String>,

    #[serde(default = "default_crs")]
    crs: String,

    #[serde(deserialize_with="odin_common::strings::deserialize_arr4")]
    bbox:[f64;4],

    width: u32,
    height: u32,

    #[serde(default = "default_format")]
    format: String,

    #[serde(default = "default_transparent")]
    transparent: bool
}

fn default_service() -> String { "WMS".into() }
fn default_version() -> String { "1.3".into() }
fn default_crs() -> String { "EPSG:4326".into() }
fn default_format() -> String { "image/tif".into() }
fn default_transparent() -> bool { false }

lazy_static! {
    #[derive(Debug)]
    static ref OPTS: CliOpts = CliOpts::from_args();
}

async fn get_map_handler (Query(q): Query<GetMapQuery>) { // just a '200 Ok' response for now
    let bbox: BoundingBox<f64> = BoundingBox::from_wsen(&q.bbox);

}

fn init_trace() {
    let tracing_layer = tracing_subscriber::fmt::layer();
    let filter = filter::Targets::new()
        .with_target("tower_http::trace::on_response", Level::TRACE)
        .with_target("tower_http::trace::on_request", Level::TRACE)
        .with_target("tower_http::trace::make_span", Level::DEBUG)
        .with_default(Level::INFO);

    tracing_subscriber::registry()
        .with(tracing_layer)
        .with(filter)
        .init();
}

#[tokio::main]
async fn main () -> Result<(),Box<dyn Error>> {
    let addr = SocketAddr::new(OPTS.ip_addr, OPTS.port);
    let mut app = Router::new()
        .route("/odin-dem/GetMap", get(get_map_handler));

    println!("response server running on http://{}/odin-dem", addr);
    println!("(terminate with ctrl-C)");

    if OPTS.verbose {
        println!("tracing turned on");
        init_trace();
        app = app.layer( TraceLayer::new_for_http());
    }

    let listener = TcpListener::bind(&addr).await?;
    axum::serve( listener, app).await?;

    Ok(())
}