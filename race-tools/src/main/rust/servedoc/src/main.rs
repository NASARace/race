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
use warp::path::FullPath;
use std::fs;
use structopt::StructOpt;

#[derive(Clone,Debug,StructOpt)]
struct Opt {
    #[structopt(long,default_value="8080")]
    port: u16,

    #[structopt(short,long)]
    verbose: bool,

    #[structopt(long,default_value="target/doc")]
    root: String,
}

lazy_static! {
    static ref OPT: Opt = Opt::from_args();
}

/// simple web server to serve target/doc contents on port 8080
/// start from repository root dir
///
/// browse documentation/slides with this instead of opening files if you want to use webrun from the displayed
/// content (otherwise it depends on the browser if it rejects cross origin requests from files)

#[tokio::main]
async fn main() {
    let routes = warp::fs::dir(OPT.root.as_str())
        .or(warp::path::full().map( move |fp: FullPath| {
                let fps = fp.as_str();
                let p = format!("{}{}", OPT.root, fps);
        
                if let Ok(metadata) = fs::metadata(p.as_str()) {
                    if metadata.is_dir() {
                        let mut response = init_response(&fps);
                        for entry in fs::read_dir(p.as_str()).unwrap() {
                            if let Ok(entry) = entry {
        
                                let path_buf = entry.path();
                                let path = path_buf.as_path();
            
                                if let Ok(metadata) = entry.metadata() {
                                    if let Some(fname) = path.file_name() {
                                        if let Some(fname) = fname.to_str() {
                                            if metadata.is_file() {
                                                if let Some(ext) = path.extension() {
                                                    if ext == "html" {
                                                        add_link(&mut response, "📄️ ", &fps, &fname);
                                                    }
                                                }
                                            } else if metadata.is_dir() {
                                                add_link(&mut response, "📁️ ", &fps, &fname);  // \u{1f4c1}
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        finish_response(&mut response);
                        warp::reply::html(response)
        
                    } else {
                        warp::reply::html( format!("can't access {}", p.as_str()))
                        //warp::reply::with_status( format!("can't access {}", p.as_str()), warp::http::StatusCode::FORBIDDEN)  // otherwise warp::fs::dir() should have served it
                    }
                } else {
                    warp::reply::html( format!("don't know about {}", p.as_str()))
                    //warp::reply::with_status( format!("don't know about {}", p.as_str()), warp::http::StatusCode::NOT_FOUND)
                }
            })
        );

    let log = warp::log::custom(|info| {
        println!("{} {} -> {}", info.method(), info.path(), info.status())
    });

    println!("serving {} on http://localhost:{}", OPT.root, OPT.port);
    println!("(terminate with ctrl-C)");

    if OPT.verbose {
        warp::serve( routes.with(log) ).run(([127, 0, 0, 1], OPT.port)).await
    } else {
        warp::serve( routes ).run(([127, 0, 0, 1], OPT.port)).await
    }
}

fn init_response (path: &str) -> String {
    let mut s = String::new();
    s.push_str("<html>\n");
    s.push_str("<body>\n");
    s.push_str("<h2>contents of directory: ");
    s.push_str(path);
    s.push_str("</h2>\n");
    s.push_str("<ul style=\"font-family:monospace;list-style-type:none;\">\n");
    s
}

fn add_link (response: &mut String, item_symbol: &str, path: &str, fname: &str) {
    response.push_str("<li>");
    response.push_str(item_symbol);
    response.push_str("<a href=\"");
    response.push_str(path);
    response.push('/');
    response.push_str(fname);
    response.push_str("\">");
    response.push_str(fname);
    response.push_str("</a></li>\n");
}

fn finish_response (response: &mut String) {
    response.push_str("</ul>\n");
    response.push_str("</body>\n");
    response.push_str("</html>");
}