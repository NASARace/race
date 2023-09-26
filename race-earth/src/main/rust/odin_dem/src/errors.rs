use thiserror::Error;
use odin_gdal::errors::OdinGdalError;

#[derive(Error,Debug)]
pub enum OdinDemError {

    #[error("unsupported target spatial ref system: {0}")]
    UnsupportedTargetSRS(String),

    // generic self-created error
    #[error("DEM operation failed: {0}")]
    OpFailedError(String),

    // pass through for IO errors
    #[error("DEM IO error: {0}")]
    IOError( #[from] std::io::Error),

    // pass through for OdinGdalErrors
    #[error("ODIN gdal error {0}")]
    OdinGdalError(#[from] OdinGdalError),

    // pass through for gdal errors
    #[error("gdal error {0}")]
    GdalError(#[from] gdal::errors::GdalError),
}