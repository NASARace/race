use thiserror::Error;

#[derive(Error,Debug)]
pub enum OdinGdalError {
    #[error("invalid file name {0}")]
    InvalidFileName(String),

    #[error("GDAL function {0} failed")]
    GdalFunctionFailed(&'static str),

    #[error("no spatial reference system")]
    NoSpatialReferenceSystem,

    #[error("failed to convert to C string {0}")]
    CStringConversion( #[from] std::ffi::NulError),

    #[error("IO error {0}")]
    IOError( #[from] std::io::Error),

    // pass through for errors in gdal crate
    #[error("gdal error {0}")]
    Error( #[from] gdal::errors::GdalError),
}