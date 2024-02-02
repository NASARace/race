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

use serde::{Deserialize, Serialize, Deserializer, Serializer};
use std::{error::Error,time::Duration};
use parse_duration::parse;
use odin_common::datetime::{serialize_duration,deserialize_duration};


#[derive(Debug, Deserialize, Serialize)]
struct BasicConfig {
    name: String,
    numbers: Vec<i64>,

    #[serde(deserialize_with="deserialize_duration",serialize_with="serialize_duration")]
    interval: Duration
}

fn main()->std::result::Result<(),Box<dyn Error>> {
    // if not initialized there should be no metadata
    let mut app_metadata = odin_config::get_app_metadata();
    assert!(app_metadata.is_none());

    // initialize from env::current_executable() filename
    //odin_config::init_from_os("gov", "nasa-odin", None)?;
    odin_config::init_from_project_root_dir("../target", "gov", "nasa-odin", Some("basic_config"));
    odin_config::ensure_dirs()?;

    app_metadata = odin_config::get_app_metadata();
    assert!(app_metadata.is_some());
    println!("{:#?}", app_metadata);

    // make sure this can't be re-initialized
    match odin_config::init_from_os("gov", "nasa", Some("something")) {
        Ok(()) => panic!("we can't initialize app metadata twice"),
        Err(e) => println!("dutifully caught error: '{:?}'", e)
    }

    // now load some config (adapt if this is not run from parent workspace)
    let conf: BasicConfig = odin_config::load_config("examples/basic_config.ron")?;
    println!("loaded config: {:#?}", conf);

    // make sure there is an XDG config dir
    let conf_dir = odin_config::config_dir()?;
    println!("app config dir at: {:?}", conf_dir);

    // now store our example config there
    match odin_config::store_config(&conf, &conf_dir, "basic_config.ron") {
        Ok(pathname) => println!("dutifully stored config to XDG dir {}", pathname),
        Err(e) => panic!("storing config to XDG dir {:?} failed with : {}", conf_dir, e)
    }

    //.. and now we can load the config from the XDG config dir
    let xdg_conf: BasicConfig = odin_config::load_from_config_dir("basic_config.ron")?;
    println!("loaded config from XDG dir: {:#?}", xdg_conf);

    Ok(())
}