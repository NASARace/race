#![allow(unused)]

//! TODO - still need to support vault (encrypted config with HashMap), plus vaultable deserializers and string accessors
//! (the latter one being more secure since they don't copy the sensitive data)

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

#[derive(Debug)]
pub struct AppMetaData {
    qualifier: String,
    organization: String,
    application: String,

    project_dirs: ProjectDirs
}

static APP_METADATA: OnceLock<AppMetaData> = OnceLock::new();

pub fn initialize (qualifier: &str, organization: &str, application: Option<&str>)->ConfigResult<()> {
    let qualifier = String::from(qualifier);
    let organization = String::from(organization);
    let application = if let Some(app) = application {
        String::from(app)
    } else {
        if let Ok(pb) = std::env::current_exe() {
            if let Some(fname) = pb.as_path().file_name().and_then(|oss| oss.to_str()) {
                String::from(fname)
            } else {
                return Err( OdinConfigError::ConfigInitError(format!("executable filename cannot be converted: {:?}",pb)))
            }
        } else {
            return Err( OdinConfigError::ConfigInitError("no executable filename".to_string()))
        }
    };

    if let Some(project_dirs) = ProjectDirs::from(qualifier.as_str(), organization.as_str(), application.as_str()) {
        let app_metadata = AppMetaData { qualifier, organization, application, project_dirs };
        APP_METADATA.set( app_metadata).map_err(|e| OdinConfigError::ConfigInitError(format!("application metadata already set")))
    } else {
        Err( OdinConfigError::ConfigInitError(format!("no home dir for {}.{}.{}", qualifier,organization,application)))
    }
}

pub fn get_app_metadata ()->Option<&'static AppMetaData> { APP_METADATA.get() }

pub fn ensure_config_dir ()->ConfigResult<String> {
    if let Some(md) = APP_METADATA.get() {
        let path = md.project_dirs.config_dir();
        if !path.is_dir() {
            std::fs::create_dir(path)?;
        }
        Ok(path.as_os_str().to_string_lossy().to_string())

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
        let dir = md.project_dirs.config_dir();
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

/* #region serde support for types not directly supported by RON ***********************************************/
/*
 * Note: don't change the Result type / map errors since the #[derive(Deserialize,Serialize)] macros would fail
 * error mapping has to occur in the function that calls serialization/deserialization 
 */

 pub fn deserialize_duration <'a,D>(deserializer: D) -> std::result::Result<Duration,D::Error>
 where D: Deserializer<'a>
{
 String::deserialize(deserializer).and_then( |string| {
     parse(string.as_str()).map_err( |e| serde::de::Error::custom(format!("{:?}",e)))
 })
}

pub fn serialize_duration <S: Serializer> (dur: &Duration, s: S) -> std::result::Result<S::Ok, S::Error>  {
    let dfm = format!("{:?}", dur); // let Duration choose the time unit
    s.serialize_str(&dfm)
}

pub fn serialize_duration_secs <S: Serializer> (dur: &Duration, s: S) -> std::result::Result<S::Ok, S::Error>  {
 let dfm = format!("{}sec", dur.as_secs());
 s.serialize_str(&dfm)
}
//... add millis, minutes, hours

//... and more to follow (DateTime, LatLon, bounding boxes etc)

/* #endregion serde support */