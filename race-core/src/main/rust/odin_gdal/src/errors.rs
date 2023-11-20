use std::ffi::CStr;
use std::ptr::null;
use thiserror::Error;
use crate::pc_char_to_string;
use gdal::errors::GdalError;
use gdal_sys::{CPLErr::CE_None, CPLErr};

pub type Result<T> = std::result::Result<T, OdinGdalError>;

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

    #[error("gdal error {0}")]
    MiscError(String),

    #[error("last gdal error {0}")]
    LastGdalError(String)
}

pub fn reset_last_gdal_error() {
    unsafe {
        gdal_sys::CPLErrorReset();
    }
}

pub fn last_gdal_err_description() -> Option<String> {
    unsafe {
        let err = gdal_sys::CPLGetLastErrorType();
        if err != gdal_sys::CPLErr::CE_None {
            let err_no = gdal_sys::CPLGetLastErrorNo();
            let p_msg = gdal_sys::CPLGetLastErrorMsg();

            gdal_sys::CPLErrorReset();

            if p_msg != null() {
                let msg_cstr = CStr::from_ptr(p_msg);
                Some(format!("{}:{}: {}", err, err_no, msg_cstr.to_string_lossy()))
            } else {
                Some(format!("{}:{}", err, err_no))
            }
        } else {
            None
        }
    }
}

pub fn gdal_error(e: GdalError) -> OdinGdalError {
    OdinGdalError::Error(e)
}

pub fn map_gdal_error <T> (res: std::result::Result<T,GdalError>) -> Result<T> {
    res.map_err(|e| OdinGdalError::Error(e))
}

pub fn last_gdal_error() -> OdinGdalError {
    OdinGdalError::LastGdalError(last_gdal_err_description().unwrap_or_else(|| "none".to_string()))
}

pub fn last_cpl_err(cpl_err_class: CPLErr::Type) -> GdalError {
    let last_err_no = unsafe { gdal_sys::CPLGetLastErrorNo() };
    let last_err_msg = pc_char_to_string(unsafe { gdal_sys::CPLGetLastErrorMsg() });
    unsafe { gdal_sys::CPLErrorReset() };
    GdalError::CplError {
        class: cpl_err_class,
        number: last_err_no,
        msg: last_err_msg,
    }
}

pub fn misc_error(s: String) -> OdinGdalError {
    OdinGdalError::MiscError(s)
}