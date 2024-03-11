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

use crate::{errors::config_error, ConfigResult, OdinConfigError};


#[derive(Debug)]
pub struct AppMetaData {
    pub domain: AppDomain,

    // those are all in the file system, to be (optionally) used at runtime.
    // NOTE that config_dir is *not* used by the config_from_..!() macro as it might be embedded
    pub config_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub data_dir: PathBuf,
}

#[derive(Debug)]
pub struct AppDomain {
    pub domain: String,  // "gov", "com", "org" etc.
    pub organization: String, // name of organization, e.g. "nasa"
    pub application: String
}
impl AppDomain {
    pub fn new ()->AppDomain {
        if let Ok(spec) = std::env::var("ODIN_APP") { // e.g. org.odin.config_lookup
            let v: Vec<&str> = spec.split('.').collect();
            if v.len() <= 2 { panic!("insufficient ODIN_APP (needs at least <domain>.<org>") }
            let domain = v[0].to_string();
            let organization = v[1].to_string();
            let application = if v.len() == 3 { v[2].to_string() } else { Self::app_name_from_executable() };
            AppDomain { domain, organization, application }

        } else {
            let domain = "org".to_string();
            let organization = "odin".to_string();
            let application = Self::app_name_from_executable();
            AppDomain { domain, organization, application }
        }
    }

    fn app_name_from_executable ()->String {
        if let Ok(pb) = std::env::current_exe() {
            if let Some(fname) = pb.as_path().file_name().and_then(|oss| oss.to_str()) {
                String::from(fname)
            } else {
                panic!("executable filename cannot be converted: {:?}",pb)
            }
        } else {
            panic!("no executable filename")
        }
    }
}

impl AppMetaData {
    pub fn new ()->Self {
        let domain = AppDomain::new();

        let (config_dir, cache_dir, data_dir) = if let Some(project_dirs) = ProjectDirs::from(&domain.domain, &domain.organization, &domain.application) {
            ( project_dirs.config_dir().to_path_buf(), 
              project_dirs.data_dir().to_path_buf(), 
              project_dirs.cache_dir().to_path_buf() )
        } else {
            ( Path::new("local/config").to_path_buf(), 
              Path::new("local/data").to_path_buf(),
              Path::new("local/cache").to_path_buf() )
        };

        ensure_dir(&config_dir).expect(format!("no valid config dir: {config_dir:?}").as_str());
        ensure_dir(&data_dir).expect(format!("no valid data dir: {data_dir:?}").as_str());
        ensure_dir(&cache_dir).expect(format!("no valid cache dir: {cache_dir:?}").as_str());

        AppMetaData { domain, config_dir, cache_dir, data_dir }
    }

    pub fn load_config <C:DeserializeOwned> (&self, pathname: impl AsRef<Path>)->ConfigResult<C> {
        let path = self.config_dir.join( pathname);
        if path.is_file() {
            let mut file = File::open(path)?;
            let len = file.metadata()?.len();
            let mut contents = String::with_capacity(len as usize);
            file.read_to_string(&mut contents)?;
            ron::from_str::<C>(contents.as_str()).map_err(|e| OdinConfigError::ConfigParseError(format!("{:?}", e)))
        } else {
            Err( OdinConfigError::ConfigFileNotFound(path.as_os_str().to_string_lossy().to_string()) )
        }
    }

    pub fn store_config <S: Serialize> (&self, conf: &S, pathname: impl AsRef<Path>)->ConfigResult<()> {
        let mut pretty_config = ron::ser::PrettyConfig::default();
        pretty_config.struct_names = true;
        pretty_config.compact_arrays = true;
    
        let serialized = ron::ser::to_string_pretty(conf, pretty_config)?;
    
        let path = self.config_dir.join( pathname);
        if let Some(parent) = path.parent() {
            ensure_dir(&parent)?;
        }

        let mut file = std::fs::File::create(path)?;
        file.write_all(serialized.as_bytes())?;
    
        Ok(())
    }

    pub fn open_config_file (&self, pathname: impl AsRef<Path>)->ConfigResult<File> {
        self.open_file(&self.config_dir, pathname)
    }

    pub fn open_data_file (&self, pathname: impl AsRef<Path>)->ConfigResult<File> {
        self.open_file(&self.data_dir, pathname)
    }

    pub fn open_cache_file (&self, pathname: impl AsRef<Path>)->ConfigResult<File> {
        self.open_file(&self.cache_dir, pathname)
    }

    pub fn create_config_file (&self, pathname: impl AsRef<Path>)->ConfigResult<File> {
        self.create_file(&self.config_dir, pathname)
    }

    pub fn create_data_file (&self, pathname: impl AsRef<Path>)->ConfigResult<File> {
        self.create_file(&self.data_dir, pathname)
    }

    pub fn create_cache_file (&self, pathname: impl AsRef<Path>)->ConfigResult<File> {
        self.create_file(&self.cache_dir, pathname)
    }

    fn open_file (&self, parent: &Path, pathname: impl AsRef<Path>)->ConfigResult<File> {
        let path = parent.join( pathname);
        if path.is_file() {
            Ok( File::open(path)?)
        } else {
            Err( OdinConfigError::ConfigFileNotFound(path.as_os_str().to_string_lossy().to_string()) )
        }
    }

    fn create_file (&self, parent: &Path, pathname: impl AsRef<Path>)->ConfigResult<File> {
        let path = parent.join( pathname);
        if let Some(parent) = path.parent() {
            ensure_dir(&parent)?;
        }
        Ok(File::create(path)?)
    }
}



