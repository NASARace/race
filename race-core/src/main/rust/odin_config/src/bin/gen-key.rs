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
#![allow(unused)]

/// small utility to create OpenPGP compatible encryption key pairs and stores them as ascii armored files.
/// 
/// This does not aim at providing the same level of configurability as `gpg --gen-key` (or
/// sequoia-openpgp tools). It does not store/import the generated key files into a keyring, which would
/// have to be done with the tools mentioned above. The sole purpose of this tool is to quickly create
/// OpenPGP compatible keys we can use to encrypt ODIN config files.
/// Generated public/private key pairs use RSA(2048) keys

use pgp::composed::{KeyType, KeyDetails, SecretKey, SecretSubkey, key::SecretKeyParamsBuilder};
use pgp::packet::{KeyFlags, UserAttribute, UserId};
use pgp::types::{PublicKeyTrait, SecretKeyTrait, CompressionAlgorithm};
use pgp::crypto::hash::HashAlgorithm;
use pgp::crypto::sym::SymmetricKeyAlgorithm;
use smallvec::*;
use anyhow::{anyhow,Result};
use lazy_static::lazy_static;
use structopt::StructOpt;
use std::path::Path;
use std::fs::File;
use std::io::Write;

#[derive(StructOpt)]
struct CliOpts {

    /// RSA key size in bits. Valid values: 2048, 4096
    #[structopt(short,long,default_value="2048")]
    key_size: u32,

    /// the directory where the public key is stored
    #[structopt(short,long,default_value="local/.pgp")]
    pub_dir: String,

    /// the directory where the private key is stored
    #[structopt(short,long,default_value="local/.pgp")]
    priv_dir: String,

    /// user name for key
    user_name: String,

    /// email for user
    user_email: String
}

lazy_static! {
    static ref OPTS: CliOpts = CliOpts::from_args();
}


fn main ()->Result<()> {
    let base_name = derive_base_name( &OPTS.user_name);
    ensure_dir(&OPTS.priv_dir)?;
    let priv_name = format!("{}/{}_private.asc", OPTS.priv_dir, base_name);
    let priv_path = Path::new( &priv_name);
    ensure_dir(&OPTS.pub_dir)?;
    let pub_name = format!("{}/{}_public.asc", OPTS.pub_dir, base_name);
    let pub_path = Path::new( &pub_name);

    let uid = format!("{} <{}>", OPTS.user_name, OPTS.user_email);
    let key_size = check_key_size(OPTS.key_size);

    let mut key_params = SecretKeyParamsBuilder::default();
    key_params
        .key_type(KeyType::Rsa(key_size))
        .can_certify(false)
        .can_sign(true)
        .primary_user_id(uid)
        .preferred_symmetric_algorithms(smallvec![ SymmetricKeyAlgorithm::AES256,])
        .preferred_hash_algorithms(smallvec![ HashAlgorithm::SHA2_256, ])
        .preferred_compression_algorithms(smallvec![ CompressionAlgorithm::ZLIB,]);
        
    let secret_key_params = key_params.build()?;
    let secret_key = secret_key_params.generate()?;
    let pass_phrase = rpassword::prompt_password("enter pass-phrase for key: ")?;
    let passwd_fn = || pass_phrase.clone();
    let signed_secret_key = secret_key.sign(passwd_fn)?;
    let armored = signed_secret_key.to_armored_string(None)?;
    set_file_contents( &priv_path, armored.as_str());
    println!("generated private key in: {priv_path:?}");

    let public_key = signed_secret_key.public_key();
    let signed_public_key = public_key.sign(&signed_secret_key, passwd_fn)?;
    let armored = signed_public_key.to_armored_string(None)?;
    set_file_contents( &pub_path, armored.as_str());
    println!("generated public key in: {pub_path:?}");

    Ok(())
}

fn derive_base_name (user_name: &str)->String {
    let mut s = user_name.replace(" ", "_");
    s.make_ascii_lowercase();
    s
}

fn ensure_dir (dir: &str)->Result<()> {
    let path = Path::new(dir);
    if !path.is_dir() {
        std::fs::create_dir_all(path)?;
    }
    Ok(())
}

fn check_key_size (ks: u32)->u32 {
    if ks >= 4096 { 4096 }
    else if ks >= 2048 { 2048 }
    else if ks >= 1024 { 1024 }
    else { 512 } 
}

fn set_file_contents (path: &Path, content: &str) {
    let mut file = File::create(path).unwrap();
    file.write_all(content.as_bytes()).unwrap();
}