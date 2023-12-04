#![allow(unused)]

use odin_config::{self,deserialize_duration,serialize_duration};

use serde::{Deserialize, Serialize, Deserializer, Serializer};
use std::{error::Error,time::Duration};
use parse_duration::parse;


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
    odin_config::initialize("gov", "nasa", None);
    app_metadata = odin_config::get_app_metadata();
    assert!(app_metadata.is_some());
    println!("{:#?}", app_metadata);

    // make sure this can't be re-initialized
    match odin_config::initialize("gov", "nasa", Some("something")) {
        Ok(()) => panic!("we can't initialize app metadata twice"),
        Err(e) => println!("dutifully caught error: '{:?}'", e)
    }

    // now load some config (adapt if this is not run from parent workspace)
    let conf: BasicConfig = odin_config::load_config("odin_config/examples/basic_config.ron")?;
    println!("loaded config: {:#?}", conf);

    // make sure there is an XDG config dir
    let conf_dir = odin_config::ensure_config_dir()?;
    println!("app config dir at: {}", conf_dir);

    // now store our example config there
    match odin_config::store_config(&conf, &conf_dir, "basic_config.ron") {
        Ok(pathname) => println!("dutifully stored config to XDG dir {}", pathname),
        Err(e) => panic!("storing config to XDG dir {} failed with : {:?}", conf_dir, e)
    }

    //.. and now we can load the config from the XDG config dir
    let xdg_conf: BasicConfig = odin_config::load_from_config_dir("basic_config.ron")?;
    println!("loaded config from XDG dir: {:#?}", xdg_conf);

    Ok(())
}