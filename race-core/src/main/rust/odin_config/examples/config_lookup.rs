#![allow(unused)]

/**
 * example application to check the various config initialization methods:
 * 
 *   (1) config_local: get config files from ./local/config/  
 *       no features need to be specified when buidling the application
 *       this is the default but should only be used for development
 * 
 *   (2) config_xdg : get config files from platform specific XDG directories
 *       build with `cargo build --features config_xdg ...`
 *       this is a production mode for machines in a controlled environment
 *       see https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html for details
 * 
 *   (3) config_embedded : this generates a _config_data_ file with compressed bytes of the local config data
 *       which is then included within the target application
 *       build with `cargo build --features config_embedded ...`
 *       note that while this does not show cleartext config data in the executable binary the data is not encrypted
 * 
 *   (4) config_embedded_pw : generate a passphrase encrypted _config_data_ file to be included in the target application
 *       build with `ODIN_PP=... cargo build --features config_embedded_pw ...`
 *       note the provided passphrase is not stored anywhere. You loose it you can't run the target application.
 *       the default encryption uses aes256
 * 
 *   (5) config_embedded_pgp : generate PGP encrypted _config_data_
 *       this is the most secure mode. Users only have to provide their public PGP keys, i.e. the secret key does not
 *       have to be shared with the developer. At runtime, the target application needs access to the users
 *       private key and the user needs to enter the respective passphrase, i.e. this is 2FA
 *       build with `ODIN_KEY=<dir>/<usr-name> cargo build --features config_embedded_pgp ...`
 *       the ODIN_KEY env var is used as a prefix to derive the key file names (<dir>/<usr-name>_{private,public}.asc)
 */

use tokio;
use odin_config::prelude::*;
use_config!();

type Result<T> = crate::ConfigResult<T>;


mod my_mod {
    use serde::Deserialize;

    #[derive(Debug,Deserialize)]
    pub struct MyConfig {
        uid: String,
        access_token: String,
        uri: String
    }
}

use my_mod::MyConfig;

#[tokio::main]
async fn main()->Result<()> {
    process_config( config_for!("my_config")?);
    Ok(())
}

fn process_config (my_config: MyConfig) {
    println!("{:#?}", my_config);
}