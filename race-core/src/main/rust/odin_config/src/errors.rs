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

use thiserror::Error;
use std::error::Error as StdError;

pub type ConfigResult<T> = std::result::Result<T, OdinConfigError>;

#[derive(Error,Debug)]
pub enum OdinConfigError {
    #[error("config directories not initialized {0}")]
    ConfigInitError(String),

    #[error("config not initialized")]
    ConfigNotInitialized,

    #[error("cannot obtain config password")]
    ConfigPwError,

    #[error("config dir not found: {0}")]
    ConfigDirNotFound(String),

    #[error("config file not found: {0}")]
    ConfigFileNotFound(String),

    #[error("config parse error {0}")]
    ConfigParseError(String),

    #[error("config write error {0}")]
    ConfigWriteError(String),

    #[error("IO error {0}")]
    IOError( #[from] std::io::Error),

    #[error("config RON error {0}")]
    RonError( #[from] ron::Error),

    #[error("config serialize/deserialize RON error {0}")]
    RonSerdeError( #[from] ron::error::SpannedError),

    #[error("decompression error {0}")]
    DecompressError(miniz_oxide::inflate::DecompressError),

    #[error("env var error {0}")]
    EnvError( #[from] std::env::VarError),

    #[error("pgp error {0}")]
    PgpError( #[from] pgp::errors::Error),

    #[error("config error {0}")]
    ConfigError(String),

    #[error("decryption error {0}")]
    DecryptionError( #[from] magic_crypt::MagicCryptError)
}

pub fn file_not_found(path: &str)->OdinConfigError {
    OdinConfigError::ConfigFileNotFound( path.to_string())
}

pub fn config_error (s: impl ToString)->OdinConfigError {
    OdinConfigError::ConfigError(s.to_string())
}

// DecompressError does not have an StdError impl (nor can we provide one here)
impl From<miniz_oxide::inflate::DecompressError> for OdinConfigError {
    fn from (e: miniz_oxide::inflate::DecompressError)->OdinConfigError {
        OdinConfigError::DecompressError(e)
    }
}