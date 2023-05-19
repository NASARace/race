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