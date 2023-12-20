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
#ifndef GDAL_UTILS_H
#define GDAL_UTILS_H

#include <gdal.h>
#include <gdal_priv.h>

template <class T> GDALDataType getGdalDataType();
size_t typeSize (GDALDataType eDT);

int gdalGetUtmZone (double lat, double lon);
GDALDataset* gdalCropToData (const char* filename, GDALDataset* pSrcDS, double noDataThreshold);
GDALDataset* gdalWarpToUtm (const char* filename, GDALDataset* pSrcDS, const char* pszDstDriverName = nullptr);
GDALDataset* gdalWarpTo4326 (const char* filename, GDALDataset* pSrcDS, const char* pszDstDriverName = nullptr);

#endif