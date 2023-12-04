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

