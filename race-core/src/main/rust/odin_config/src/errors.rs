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

pub type Result<T> = std::result::Result<T, OdinConfigError>;

#[derive(Error,Debug)]
pub enum OdinConfigError {
    #[error("config directories not initialized {0}")]
    ConfigInitError(String),

    #[error("config not initialized")]
    ConfigNotInitialized,

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

    #[error("config serialize/deserialize RON error {0}")]
    RonError( #[from] ron::Error),
}

