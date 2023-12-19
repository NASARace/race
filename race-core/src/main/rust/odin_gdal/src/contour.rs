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
use std::ffi::{CString,CStr};
use std::path::{Path, PathBuf};
use std::fs;
use std::ptr::{null, null_mut};
use gdal::Dataset;
use gdal::cpl::CslStringList;
use gdal::spatial_ref::SpatialRef;
use gdal_sys::GDALTermProgress;
use gdal_sys::OGRFieldType::OFTInteger;
use gdal_sys::{GDALDatasetH, OGRDataSourceH, GDALContourGenerateEx, CSLConstList, GDALGetRasterBand, OGR_DS_CreateLayer, OGRLayerH, OGRwkbGeometryType, OGR_L_GetLayerDefn};
use libc::{c_int, c_uint};
use crate::get_driver_name_from_filename;
use crate::errors::{Result, last_gdal_error, misc_error, OdinGdalError, reset_last_gdal_error};

pub struct ContourBuilder <'a> {
    src_ds: &'a Dataset,
    tgt_filename: CString,
    band_no: Option<c_int>,
    src_band: Option<gdal_sys::GDALRasterBandH>,
    tgt_layer_name: CString,
    field_id_index: Option<c_int>,
    interval: Option<c_int>,
    attr_min_name: Option<CString>,
    attr_min_id: Option<c_int>,
    attr_max_name: Option<CString>,
    attr_max_id: Option<c_int>,
    attr_name: Option<CString>,
    attr_id: Option<c_int>,
    polygonize: bool,
    three_d: bool
}

impl <'a> ContourBuilder<'a> {
    pub fn new <P: AsRef<Path>>(src_ds: &'a Dataset, tgt: P) -> Result<ContourBuilder<'a>> {
        let path = tgt.as_ref();
        let tgt_str = path.to_str().ok_or(OdinGdalError::InvalidFileName(path.display().to_string()))?;
        let tgt_filename = CString::new(tgt_str)?;
        let tgt_layer_name = CString::new("contour")?;

        Ok(ContourBuilder {
            src_ds: src_ds,
            tgt_filename: tgt_filename,
            band_no: None,
            src_band: None,
            tgt_layer_name : tgt_layer_name,
            field_id_index: None,
            interval: None,
            attr_min_name: None,
            attr_min_id: None,
            attr_max_name: None,
            attr_max_id: None,
            attr_name: None,
            attr_id: None,
            polygonize: false,
            three_d: false
        })
    }

    pub fn set_band (&mut self, band: i32) -> &mut ContourBuilder<'a> { 
        unsafe {
            self.band_no = Some(band);
            self.src_band = Some(GDALGetRasterBand(self.src_ds.c_dataset(), band));
            self
        }
    }

    pub fn set_interval (&mut self, interval: i32) -> &mut ContourBuilder<'a>  {
        self.interval = Some(interval);
        self
    }

    pub fn set_poly (&mut self) -> &mut ContourBuilder<'a> {
        self.polygonize = true;
        self
    }

    pub fn set_tgt_layer_name(&mut self, name: &str) -> Result<&mut ContourBuilder<'a>>  {
        self.tgt_layer_name = CString::new(name)?;
        Ok(self)
    }

    pub fn set_3_d(&mut self) -> &mut ContourBuilder<'a>  {
        self.three_d = true;
        self
    }

    pub fn set_attr_name (&mut self, name: &str) -> Result<&mut ContourBuilder<'a>>  {
        self.attr_name = Some(CString::new(name)?);
        Ok(self)
    }

    pub fn set_attr_min_name(&mut self, name: &str) -> Result<&mut ContourBuilder<'a>>  {
        self.attr_min_name = Some(CString::new(name)?);
        Ok(self)
    }


    pub fn set_attr_max_name (&mut self, name: &str) -> Result<&mut ContourBuilder<'a>>  {
        self.attr_max_name = Some(CString::new(name)?);
        Ok(self)
    }

    fn create_tgt_ds (& self) -> Result<OGRDataSourceH> { // split into create tgt_dataset and create tgt_layer - can close dataset after?
        unsafe{
            // create target dataset 
            // 1. get driver from file 
            let tgt_format: &str = if let Some(driver_name) = get_driver_name_from_filename(self.tgt_filename.as_c_str().to_str().unwrap()) { // self.tgt_filename checked during new
                driver_name
            } else {
                "GeoJSON" // our last fallback
            };
            let c_tgt_format = CString::new(tgt_format)?;
            let c_driver = gdal_sys::GDALGetDriverByName(c_tgt_format.as_ptr());
            if c_driver == null_mut() {
                return Err(misc_error(format!("unknown output format {:?}", c_tgt_format)))
            }
            // 2. create data source (input driver, filename) - OGR_Dr_CreateDataSource
            let tgt_ds = gdal_sys::OGR_Dr_CreateDataSource(c_driver, self.tgt_filename.as_ptr(), null_mut());
            Ok(tgt_ds)
        }
        
    }

    fn create_tgt_layer(&mut self, tgt_ds: OGRDataSourceH) -> Result<OGRLayerH> {
        unsafe{
            // create target layer
            // 1. create destination layer (hlayer) - add elevation attributes if needed - OGR_DS_CreateLayer
            let srs = gdal_sys::GDALGetSpatialRef(self.src_ds.c_dataset()); // get srs from src
            let poly_type = if self.polygonize { // get polygon type
                if self.three_d {
                    OGRwkbGeometryType::wkbMultiPolygon25D
                } else {
                    OGRwkbGeometryType::wkbMultiPolygon
                }
            } else {
                if self.three_d {
                    OGRwkbGeometryType::wkbLineString25D
                } else {
                    OGRwkbGeometryType::wkbLineString
                }
            };
            
            let tgt_layer = OGR_DS_CreateLayer(tgt_ds, self.tgt_layer_name.as_ptr(), srs, poly_type, null_mut());
            
            // 2. create elevation attribute - check for conflicts with polygonize
            if self.polygonize {
                if self.attr_name.is_some(){
                    self.attr_name = None;
                    return Err(OdinGdalError::MiscError("-a is ignored in polygonal contouring mode. use -amin/-amax instead.".to_string()))
                }
            } else {
                if self.attr_max_name.is_some() | self.attr_min_name.is_some() {
                    self.attr_max_name = None;
                    self.attr_min_name = None;
                    return Err(OdinGdalError::MiscError("-amin/-amax is ignored in line contouring mode. use -a instead.".to_string()))
                }
            }
            if let Some(name) = &self.attr_name {
                self.create_elev_attr(name.clone(), tgt_layer);
                self.attr_id = Some(gdal_sys::OGR_FD_GetFieldIndex(OGR_L_GetLayerDefn(tgt_layer), name.clone().as_ptr()));
            }
            if let Some(min_name) = &self.attr_min_name {
                self.create_elev_attr(min_name.clone(), tgt_layer);
                self.attr_min_id = Some(gdal_sys::OGR_FD_GetFieldIndex(OGR_L_GetLayerDefn(tgt_layer), min_name.clone().as_ptr()));
            }
            if let Some(max_name) = &self.attr_max_name {
                self.create_elev_attr(max_name.clone(), tgt_layer);
                self.attr_max_id = Some(gdal_sys::OGR_FD_GetFieldIndex(OGR_L_GetLayerDefn(tgt_layer), max_name.clone().as_ptr()));
            }

            // 3. add id field - needs to be after elevation to work? original cpp has it before 
            let id = CString::new("ID")?;
            let h_fld = gdal_sys::OGR_Fld_Create(id.as_ptr(), OFTInteger);
            gdal_sys::OGR_Fld_SetWidth(h_fld, 8);
            gdal_sys::OGR_L_CreateField(tgt_layer, h_fld, 0);
            gdal_sys::OGR_Fld_Destroy(h_fld);
            self.field_id_index =  Some(gdal_sys::OGR_FD_GetFieldIndex(OGR_L_GetLayerDefn(tgt_layer), id.as_ptr()));

            Ok(tgt_layer)
        }
    }

    fn create_elev_attr(&self, attr:  CString, tgt_layer: OGRLayerH) -> Result<()>{
        unsafe{
            let new_fld = gdal_sys::OGR_Fld_Create(attr.as_ptr(), gdal_sys::OGRFieldType::OFTReal);
            let field_err = gdal_sys::OGR_L_CreateField(tgt_layer, new_fld, 0);
            gdal_sys::OGR_Fld_Destroy(new_fld);
            if (field_err == gdal_sys::OGRErr::OGRERR_FAILURE){
                return Err(OdinGdalError::MiscError("error creating elevation field in target".to_string()));
            }
        }
        Ok(())
    }

    pub fn exec(&mut self)-> Result<()> {
        let tgt_ds = self.create_tgt_ds()?;
        let tgt_layer = self.create_tgt_layer(tgt_ds)?;
        let options = self.build_options()?;
        self.contour(tgt_layer, tgt_ds, options)
    }

    fn contour(&self, tgt_layer: OGRLayerH, tgt_ds: OGRDataSourceH, options: CslStringList) -> Result<()> {
        unsafe {
            reset_last_gdal_error();
            // invoke
            let band = if let Some(src_band) = self.src_band {
                src_band
            } else {
                return Err(OdinGdalError::MiscError("error: no source band set for contour operations".to_string()))
            };
            let c_contour_op = gdal_sys::GDALContourGenerateEx(band, tgt_layer, options.as_ptr(), Some(GDALTermProgress), null_mut());
            // destroy
            if c_contour_op == gdal_sys::CPLErr::CE_None {
                gdal_sys::GDALFlushCache(tgt_ds); // close target
                gdal_sys::GDALFlushCache(self.src_ds.c_dataset()); // close source
                Ok(())
            } else {
                Err(last_gdal_error())
            }
        }
    }
    
    fn build_options(&self) -> Result<CslStringList> {
        unsafe {
            let mut options = CslStringList::new();
            if let Some(attr_id) = self.attr_id {
                options.add_string(&format!("ELEV_FIELD={:?}", attr_id).to_string());
            }
            if let Some(attr_max_id) = self.attr_max_id{
                options.add_string(&format!("ELEV_FIELD_MAX={:?}", attr_max_id).to_string());
            }
            if let Some(attr_min_id) = self.attr_min_id{
                options.add_string(&format!("ELEV_FIELD_MIN={:?}", attr_min_id).to_string());
            }
            if self.polygonize {
                options.add_string("POLYGONIZE=YES");
            }
            options.add_string(&format!("ID_FIELD={:?}", self.field_id_index.unwrap()).to_string());
            if let Some(interval) = self.interval{
                options.add_string(&format!("LEVEL_INTERVAL={}", interval).to_string());
            } else {
                return Err(OdinGdalError::MiscError("no interval set for contour operations".to_string()))
            };
            Ok(options)
        }
    }
}


