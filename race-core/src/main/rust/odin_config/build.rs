/*
 * Copyright (c) 2024, United States Government, as represented by the
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

/// build script (example) of how to generate embedded configuration data
///
/// Based on requested features ("config_embedded", "config_embedded_gpg") this uses either the value of an
/// IN_DIR env or ./local/config as a fallback to turn *.ron configuration files into byte vectors that can be
/// included (by means of the odin_config prelude) into respective crates to build stand-alone applications
/// that do not require - or should hardwire - config files.
///
/// Note this requires a miniz_oxide build-dependency in respective Cargo.toml files

use std::env;
use std::fs;
use std::path::Path;
use std::path::PathBuf;
use std::io::Read;
use std::fmt::write;

use miniz_oxide::deflate::compress_to_vec;

// this is a hack to avoid the warning output from Cargo. Hopefully Cargo will some day support info directly
macro_rules! info {
    ($($tokens: tt)*) => {
        println!("cargo:warning=\r\x1b[32;1m  \x1b[37m info: {}\x1b[0m", format!($($tokens)*))
    }
}

enum ConfigMode {
    File,         // look up config_for!(..) files in ./local/config
    Embedded,     // use config data embedded in generated _config_data_ module (stored as const [u8;N])
    EmbeddedPGP,  // use GPG encrypted config data embedded in generated _config_data_ module (stored as const [u8;N])
    EmbeddedPw,   // use embedded data encrypted with Argon2 / provided user password
    Xdg,          // look up config_for!(..) files in (platform specific) XDG directories 
}

fn main() {
    let out_dir = env::var("OUT_DIR").unwrap(); // this should always be defined by cargo
    let in_dir = if let Ok(dir) = env::var("IN_DIR") { dir } else { "./local/config".to_string() }; // this is ours and needs a default
    let out_file: PathBuf = [out_dir.as_str(), "config_data"].iter().collect();

    match  get_config_mode() {
        ConfigMode::File => {
            info!("building in local config mode");
            // nothing to do
        }
        ConfigMode::Xdg => {
            info!("building in XDG mode");
            // nothing to do
        }
        ConfigMode::Embedded => {
            info!("building in embedded config mode");
            info!("using input dir {in_dir}");
            let src = get_config_src( Path::new(&in_dir), |input| compress_to_vec(input, 6));
            if !src.is_empty() {
                fs::write( &out_file, src).unwrap();
                info!("generated config data {}", out_file.to_string_lossy());
            }
        }
        ConfigMode::EmbeddedPGP => {
            if let Ok(mut pub_key) = env::var("ODIN_KEY") { // this is the dir/name combination, e.g. local/.pgp/otto
                if !pub_key.ends_with("_public.asc") { pub_key.push_str("_public.asc") }

                info!("building in embedded PGP config mode");
                info!("using public PDP key from: {pub_key}");
                let src = get_config_src( Path::new(&in_dir), |input| pgp_encrypt(input, pub_key.as_str()));
                if !src.is_empty() {
                    fs::write( &out_file, src).unwrap();
                    info!("generated PGP encrypted config data {}", out_file.to_string_lossy());
                }
            } else { 
                panic!("ODIN_KEY not set, terminating build.");
            }
        }
        ConfigMode::EmbeddedPw => {
            if let Ok(passphrase) = env::var("ODIN_PP") {
                info!("building in embedded passphrase config mode");
                let src = get_config_src( Path::new(&in_dir), move |input| {
                    use magic_crypt::MagicCryptTrait;
                    let mc = magic_crypt::new_magic_crypt!(passphrase.clone(),256);
                     mc.encrypt_to_bytes(input)
                });

                if !src.is_empty() {
                    fs::write( &out_file, src).unwrap();
                    info!("generated passphrase encrypted config data {}", out_file.to_string_lossy());
                }
            } else { 
                panic!("ODIN_PP not set, terminating build.");
            }
        }
    }
    
    println!("cargo:rerun-if-changed=build.rs");
}

fn get_config_mode()->ConfigMode {
    if cfg!(feature = "config_embedded") { ConfigMode::Embedded } 
    else if cfg!( feature = "config_embedded_pgp") { ConfigMode::EmbeddedPGP }
    else if cfg!( feature = "config_embedded_pw") { ConfigMode::EmbeddedPw }
    else if cfg!( feature = "config_xdg") { ConfigMode::Xdg } 
    else { ConfigMode::File }
}

fn get_config_src<F> (config_dir: &Path, process_content: F)->String 
    where F: Fn(&[u8])->Vec<u8> 
{
    if !config_dir.is_dir() { panic!("config source dir not found: {}", config_dir.to_string_lossy()) }

    let mut config_data = String::with_capacity(2048);
    write( &mut config_data, format_args!("fn __c_data__ (id: &str)->Option<&[u8]> {{\n")).unwrap();

    for entry in fs::read_dir( config_dir).unwrap() {
        if let Ok(e) = entry {
            let path = e.path();
            if path.is_file() {
                let file_name = path.file_name().unwrap().to_str().unwrap();
                if file_name.ends_with(".ron") {
                    info!("processing file {}", path.to_str().unwrap());
                    let var_name = unsafe { file_name.get_unchecked(0..file_name.len()-4) };
                    let bytes_in = file_contents_as_bytes(&path);
                    let bytes_out = process_content(bytes_in.as_ref());
                    write( &mut config_data, format_args!("if id==\"{var_name}\" {{ return Some(&{bytes_out:?}) }}")).unwrap();
                }
            }
        } 
    }
    write( &mut config_data, format_args!("None }}\n")).unwrap();

    config_data
}


fn file_contents_as_bytes (path: &Path) -> Vec<u8> {
    if !path.is_file() { panic!("not a file: {:?}", path) }

    let mut file = fs::File::open(path).unwrap();
    let len = file.metadata().unwrap().len();
    let mut contents: Vec<u8> = Vec::with_capacity(len as usize);
    file.read_to_end(&mut contents).unwrap();
    contents
}

//--- PGP encryption

use pgp::Deserializable;
use rand::SeedableRng;

fn pgp_encrypt (data: &[u8], pub_key_filename: &str) -> Vec<u8> {
    let mut pub_key_file = fs::File::open(pub_key_filename).expect("cannot open public key file");

    let (pub_key, _headers) = pgp::composed::SignedPublicKey::from_armor_single(&mut pub_key_file).expect("failed to read key");
    pub_key.verify().expect("invalid public key");

    let lit_msg = pgp::composed::Message::new_literal_bytes("_config_data_", data);
    let compressed_msg = lit_msg.compress(pgp::types::CompressionAlgorithm::ZLIB).unwrap();

    let mut rng = rand::rngs::StdRng::seed_from_u64(100);
    let encrypted = compressed_msg
            .encrypt_to_keys(&mut rng, pgp::crypto::sym::SymmetricKeyAlgorithm::AES128, &[&pub_key.primary_key][..])
            .unwrap();
    let armored = encrypted.to_armored_bytes(None).unwrap();

    armored
}