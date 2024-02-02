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

pub mod errors;
use crate::errors::{OdinConfigError,Result as ConfigResult};

use std::{path::{Path,PathBuf}, fs::File, io::{Read, Write}, time::Duration, sync::OnceLock};
use ron;
use serde::{
    Deserialize,Serialize,
    Deserializer,Serializer,
    de::DeserializeOwned
};
use parse_duration::parse;
use directories::ProjectDirs;
use odin_common::fs::ensure_dir;

#[derive(Debug)]
pub struct AppMetaData {
    qualifier: String,
    organization: String,
    application: String,

    config_dir: PathBuf,
    cache_dir: PathBuf,
    data_dir: PathBuf,
}

static APP_METADATA: OnceLock<AppMetaData> = OnceLock::new();

pub fn init_from_os (qualifier: &str, organization: &str, application: Option<&str>)->ConfigResult<()> {
    let qualifier = String::from(qualifier);
    let organization = String::from(organization);
    let application = if let Some(app) = application { String::from(app) } else { app_from_env()? };

    if let Some(project_dirs) = ProjectDirs::from(qualifier.as_str(), organization.as_str(), application.as_str()) {
        let config_dir = project_dirs.config_dir().to_path_buf();
        let data_dir = project_dirs.data_dir().to_path_buf();
        let cache_dir = project_dirs.cache_dir().to_path_buf();

        let app_metadata = AppMetaData { qualifier, organization, application, config_dir, cache_dir, data_dir };
        APP_METADATA.set( app_metadata).map_err(|e| OdinConfigError::ConfigInitError(format!("application metadata already set")))
    } else {
        Err( OdinConfigError::ConfigInitError(format!("no home dir for {}.{}.{}", qualifier,organization,application)))
    }
}

/// watch out - this overrides XDG and should only be used for testing or special installations
pub fn init_from_project_root_dir (path: impl Into<PathBuf>, qualifier: &str, organization: &str, application: Option<&str>)->ConfigResult<()> {
    let qualifier = String::from(qualifier);
    let organization = String::from(organization);
    let application = if let Some(app) = application { String::from(app) } else { app_from_env()? };
    let project_dir = path.into().join(&application);

    let config_dir = project_dir.join("config");
    let data_dir = project_dir.join("data");
    let cache_dir = project_dir.join("cache");

    let app_metadata = AppMetaData { qualifier, organization, application, config_dir, cache_dir, data_dir };
    APP_METADATA.set( app_metadata).map_err(|e| OdinConfigError::ConfigInitError(format!("application metadata already set")))
}

fn app_from_env ()->ConfigResult<String> {
    if let Ok(pb) = std::env::current_exe() {
        if let Some(fname) = pb.as_path().file_name().and_then(|oss| oss.to_str()) {
            Ok(String::from(fname))
        } else {
            Err( OdinConfigError::ConfigInitError(format!("executable filename cannot be converted: {:?}",pb)))
        }
    } else {
        Err( OdinConfigError::ConfigInitError("no executable filename".to_string()))
    }
}

pub fn get_app_metadata ()->Option<&'static AppMetaData> { APP_METADATA.get() }

pub fn ensure_dirs ()->ConfigResult<()> {
    if let Some(md) = APP_METADATA.get() {
        ensure_dir( &md.cache_dir)?;
        ensure_dir( &md.data_dir)?;
        ensure_dir( &md.cache_dir)?;
        Ok(())
    } else {
        Err(OdinConfigError::ConfigNotInitialized)
    }
}

pub fn config_dir()->ConfigResult<&'static PathBuf> {
    if let Some(md) = APP_METADATA.get() {
        ensure_dir( &md.config_dir)?;
        Ok(&md.config_dir)
    } else {
        Err(OdinConfigError::ConfigNotInitialized)
    }
}

pub fn data_dir()->ConfigResult<&'static PathBuf> {
    if let Some(md) = APP_METADATA.get() {
        ensure_dir( &md.data_dir)?;
        Ok(&md.data_dir)
    } else {
        Err(OdinConfigError::ConfigNotInitialized)
    }
}

pub fn cache_dir()->ConfigResult<&'static PathBuf> {
    if let Some(md) = APP_METADATA.get() {
        ensure_dir( &md.cache_dir)?;
        Ok(&md.cache_dir)
    } else {
        Err(OdinConfigError::ConfigNotInitialized)
    }
}

pub fn load_config <C:DeserializeOwned> (pathname: impl AsRef<Path>)->ConfigResult<C> {
    let path = pathname.as_ref();
    if !path.is_file() {
        Err( OdinConfigError::ConfigFileNotFound(path.as_os_str().to_string_lossy().to_string()) )
    } else {
        let mut file = File::open(path)?;

        let len = file.metadata()?.len();
        let mut contents = String::with_capacity(len as usize);
        file.read_to_string(&mut contents)?;

        ron::from_str::<C>(contents.as_str()).map_err(|e| OdinConfigError::ConfigParseError(format!("{:?}", e)))
    }
}

pub fn load_from_config_dir <C:DeserializeOwned> (fname: impl AsRef<Path>)->ConfigResult<C> {
    if let Some(md) = APP_METADATA.get() {
        let dir = &md.config_dir;
        if !dir.is_dir() {
           Err( OdinConfigError::ConfigDirNotFound(dir.as_os_str().to_string_lossy().to_string()) )
        } else {
            let mut path_buf = PathBuf::new();
            path_buf.push(dir);
            path_buf.push(fname);

            if path_buf.is_file() {
                load_config(path_buf)
            } else {
                Err( OdinConfigError::ConfigFileNotFound(path_buf.as_os_str().to_string_lossy().to_string()) )
            }
        }

    } else {
        Err(OdinConfigError::ConfigNotInitialized)
    }
}

pub fn store_config <S: Serialize> (conf: &S, dir: impl AsRef<Path>, fname: impl AsRef<Path>)->ConfigResult<String> {
    let mut pretty_config = ron::ser::PrettyConfig::default();
    pretty_config.struct_names = true;
    pretty_config.compact_arrays = true;

    let serialized = ron::ser::to_string_pretty(conf, pretty_config)?;

    let mut path_buf = PathBuf::new();
    path_buf.push(dir);
    path_buf.push(fname);
    let pathname = path_buf.as_os_str().to_string_lossy().to_string();

    let mut file = std::fs::File::create(path_buf)?;
    file.write_all(serialized.as_bytes())?;

    Ok(pathname)
}
