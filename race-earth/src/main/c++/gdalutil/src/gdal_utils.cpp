
#include <iostream>

#include <gdal.h>
#include <gdal_alg.h>
#include <gdal_priv.h>
#include <ogr_geometry.h>
#include <ogr_spatialref.h>
#include <ogrsf_frmts.h>
#include <gdalwarper.h>

#include "gdal_utils.h"

using std::cerr;



size_t typeSize (GDALDataType eDT) {
    switch (eDT) {
        case GDT_Byte:
            return 1;

        case GDT_Int16:
        case GDT_UInt16: 
            return 2;

        case GDT_Int32:
        case GDT_UInt32:
        case GDT_Float32:
            return 4;

        case GDT_Int64:
        case GDT_UInt64:
        case GDT_Float64:
            return 8;

        default:
            throw std::runtime_error("unknown type size");
    }
}

template <class T> GDALDataType getGdalDataType() { return GDT_Unknown; }
template<> GDALDataType getGdalDataType<double>() { return GDT_Float64; }
template<> GDALDataType getGdalDataType<float>() { return GDT_Float32; }
template<> GDALDataType getGdalDataType<short>() { return GDT_Int16; }
template<> GDALDataType getGdalDataType<unsigned short>() { return GDT_UInt16; }
template<> GDALDataType getGdalDataType<int>() { return GDT_Int32; }
template<> GDALDataType getGdalDataType<unsigned>() { return GDT_UInt32; }
template<> GDALDataType getGdalDataType<long>() { return GDT_Int64; }
template<> GDALDataType getGdalDataType<unsigned long>() { return GDT_UInt64; }


void copyNoDataValue (GDALRasterBand* srcBand, GDALRasterBand* dstBand) {
    GDALDataType bandDT = srcBand->GetRasterDataType();
    int hasNoData = 0;
    switch (bandDT) {
        case GDT_UInt64:
        {
            uint64_t ndv = srcBand->GetNoDataValueAsUInt64(&hasNoData);
            if (hasNoData) dstBand->SetNoDataValueAsUInt64(ndv);
        }
        case GDT_Int64:
        {
            int64_t ndv = srcBand->GetNoDataValueAsInt64(&hasNoData);
            if (hasNoData) dstBand->SetNoDataValueAsInt64(ndv);
        }
        default:
        {
            double ndv = srcBand->GetNoDataValue(&hasNoData);
            if (hasNoData) dstBand->SetNoDataValue(ndv);
        }
    } 
}

// return UTM zone 1-60 or -1 if illegal input
int gdalGetUtmZone (double lat, double lon) {

    // handle special cases (Svalbard/Norway)
    if (lat > 55 && lat < 64 && lon > 2 && lon < 6) {
        return 32;
    }

    if (lat > 71) {
        if (lon >= 6 && lon < 9) {
            return 31;
        }
        if ((lon >= 9 && lon < 12) || (lon >= 18 && lon < 21)) {
            return 33;
        }
        if ((lon >= 21 && lon < 24) || (lon >= 30 && lon < 33)) {
            return 35;
        }
    }

    if (lon >= -180 && lon <= 180) {
        return ((int)((lon + 180.0) / 6.0) % 60) + 1;
    } else if (lon > 180 && lon < 360) {
        return ((int)(lon / 6.0) % 60) + 1;
    }

    return -1;
}

inline bool isNoDataValue (double d, double noDataValue) {
    return std::abs(d - noDataValue) < 1E-6;
}

int countConsecutiveNoData (int nX, double* scanLine, double noDataValue) {
    int nNoData = 0;
    int n = 0;

    for (int i=0; i<nX; i++) {
        if (isNoDataValue(scanLine[i], noDataValue)) {
            n++;
            if (n > nNoData) nNoData = n;
        } else {
            n = 0;
        }
    }

    return nNoData;
}

int countLeadingNoData (int nX, double* scanLine, double noDataValue) {
    int n = 0;
    for (; n<nX && isNoDataValue(scanLine[n],noDataValue); n++);
    return n;
}

int countTrailingNoData (int nX, double* scanLine, double noDataValue) {
    int n = 0;
    for (int i=nX-1; i>=0 && isNoDataValue(scanLine[i],noDataValue); i--) n++;
    return n;
}

bool gdalGetDataBoundaries (GDALDataset* pDS, int bandNr, double noDataThreshold, int& minRow, int& maxRow, int& minCol, int& maxCol) {
    if (pDS->GetRasterCount() >= bandNr) {
        int nX = pDS->GetRasterXSize();
        int nY = pDS->GetRasterYSize();

        minRow = 0;
        maxCol = 0;
        maxRow = nY-1;
        maxCol = nX-1;

        GDALRasterBand* band = pDS->GetRasterBand(bandNr);
        int hasNoDataValue = 0;
        double noDataValue = band->GetNoDataValue(&hasNoDataValue);

        if (hasNoDataValue) {
            int maxNoDataPerLine = std::round(nX * noDataThreshold);
            std::unique_ptr<double[]> scanLine( new double[nX]);

            for (; minRow<nY; minRow++) {
                if (band->RasterIO(GF_Read, 0,minRow, nX,1, scanLine.get(), nX,1,GDT_Float64, 0,0,nullptr) != CE_None) return false;
                if (countConsecutiveNoData(nX, scanLine.get(), noDataValue) < maxNoDataPerLine) break;
            }

            if (minRow < maxRow) {
                for (; maxRow > minRow; maxRow--) {
                    if (band->RasterIO(GF_Read, 0,maxRow, nX,1, scanLine.get(), nX,1,GDT_Float64, 0,0,nullptr) != CE_None) return false;
                    if (countConsecutiveNoData(nX, scanLine.get(), noDataValue) < maxNoDataPerLine) break;
                }

                int rightMargin = 0;
                for (int i=minRow; i<=maxRow; i++) {
                    if (band->RasterIO(GF_Read, 0,i, nX,1, scanLine.get(), nX,1,GDT_Float64, 0,0,nullptr) != CE_None) return false;
                    minCol = std::max( countLeadingNoData( nX, scanLine.get(), noDataValue), minCol);

                    if (minCol < maxNoDataPerLine) {
                        rightMargin = std::max( countTrailingNoData(nX, scanLine.get(), noDataValue), rightMargin);
                        if (rightMargin < maxNoDataPerLine) {
                            maxCol = std::min( nX-1 - rightMargin, maxCol);

                            for (int j=minCol; j<=maxCol; j++) {
                                if (isNoDataValue(scanLine.get()[j], noDataValue)){
                                    cerr << "interior noData values\n";
                                    return false;
                                }
                            }
                        } else {
                            cerr << "right noData margin exceeded in line " << i << " : " << rightMargin << "\n";
                            return false; 
                        }

                    } else {
                        cerr << "left noData margin exceeded in line " << i << " : " << minCol << "\n";
                        return false;
                    }
                }

                return true; // successfully established defined data sub rectangle

            } else {
                cerr << "raster band has no data\n";
                return false;
            }

        } else { // there is no noData in this band
            return true;
        }

    } else {
        cerr << "invalid band\n";
        return false;
    }
}


GDALDataset* gdalCrop (const char* filename, GDALDataset* pSrcDS, int minRow, int maxRow, int minCol, int maxCol) {
    GDALDriver* pDriver = pSrcDS->GetDriver();
    int nBands = pSrcDS->GetRasterCount();
    GDALDataType eDT = pSrcDS->GetRasterBand(1)->GetRasterDataType();

    double a[6];
    pSrcDS->GetGeoTransform(a);
    const char *pszSrcWKT = pSrcDS->GetProjectionRef();

    int nPixels = maxCol - minCol +1;
    int nLines = maxRow - minRow +1;

    GDALDataset* pDstDS = pDriver->Create( filename, nPixels, nLines, nBands, eDT, NULL );
    if (pDstDS) {
        void* scanLine = CPLMalloc( typeSize(eDT) * nPixels);

        for (int bandNo=1; bandNo<=nBands; bandNo++) {
            GDALRasterBand* srcBand = pSrcDS->GetRasterBand(bandNo);
            GDALRasterBand* dstBand = pDstDS->GetRasterBand(bandNo);
        
            for (int i=minRow, j=0; i<=maxRow; i++, j++) {
                if (srcBand->RasterIO(GF_Read, minCol,i, nPixels,1, scanLine, nPixels,1,eDT, 0,0,nullptr) == CE_None) {
                    if (dstBand->RasterIO(GF_Write, 0,j, nPixels,1, scanLine, nPixels,1,eDT, 0,0,nullptr) != CE_None) {
                        cerr << "error writing crop scanline of band " << bandNo << "\n";
                        delete pDstDS;
                        pDstDS = nullptr;
                        break;   
                    }
                } else {
                    cerr << "error reading source scanline of band " << bandNo << "\n";
                    delete pDstDS;
                    pDstDS = nullptr;
                    break;
                }
            }

            copyNoDataValue( srcBand, dstBand);
        }

        a[0] += minCol * a[1]; // left
        a[3] += minRow * a[5];  // upper, a[5] is negative for north-up

        pDstDS->SetGeoTransform(a);
        pDstDS->SetProjection(pszSrcWKT);

        CPLFree( scanLine);
        pDstDS->FlushCache();
        return pDstDS;

    } else {
        cerr << "error creating crop dataset\n";
        return nullptr;
    }
}

GDALDataset* gdalCropToData (const char* filename, GDALDataset* pSrcDS, double noDataThreshold) {
    int minCol=0, maxCol=0, minRow=0, maxRow=0;
    if (gdalGetDataBoundaries(pSrcDS, 1, noDataThreshold, minRow,maxRow,minCol,maxCol)) {
        int nPixels = pSrcDS->GetRasterXSize();
        int nRows = pSrcDS->GetRasterYSize();

        if (minCol || minRow || (maxCol < nPixels-1) || (maxRow < nRows-1)) {
            return gdalCrop(filename, pSrcDS, minRow, maxRow, minCol, maxCol);
        } else {
            return pSrcDS; // nothing to crop, return source
        }

    } else {
        return nullptr;
    }
}


static GDALDataset* warp (GDALDataset* pSrcDS, const char* pszDstWKT, GDALDriver* pDriver, const char* filename) {
    GDALDataset* pDstDS = nullptr, *ret = nullptr;

    int nBands = pSrcDS->GetRasterCount();
    const char *pszSrcWKT = pSrcDS->GetProjectionRef();

    void* hTransformArg = GDALCreateGenImgProjTransformer( pSrcDS, pszSrcWKT, NULL, pszDstWKT, FALSE, 0, 0);
    if (hTransformArg) {
        double adfDstGeoTransform[6];

        int nPixels = 0, nLines = 0; 
        GDALSuggestedWarpOutput( pSrcDS, GDALGenImgProjTransform, hTransformArg, adfDstGeoTransform, &nPixels, &nLines );
        GDALDestroyGenImgProjTransformer( hTransformArg );

        GDALDataType eDT = GDALGetRasterDataType(GDALGetRasterBand(pSrcDS,1));
        double dstNoData = -9999;

        pDstDS = pDriver->Create( filename, nPixels, nLines, nBands, eDT, NULL );
        if (pDstDS) {
            pDstDS->SetProjection(pszDstWKT);
            pDstDS->SetGeoTransform(adfDstGeoTransform);
            pDstDS->GetRasterBand(1)->SetNoDataValue(dstNoData);

            hTransformArg = GDALCreateGenImgProjTransformer( pSrcDS, NULL, pDstDS, NULL, FALSE, 0, 1);
            char** papszOptions = nullptr;
            CPLStringList opts(papszOptions, FALSE);
            opts.AddNameValue("INIT_DEST","NO_DATA");

            GDALWarpOptions *warpOpts = GDALCreateWarpOptions();
            warpOpts->hSrcDS = GDALDataset::ToHandle(pSrcDS);
            warpOpts->hDstDS = GDALDataset::ToHandle(pDstDS);

            warpOpts->papszWarpOptions = papszOptions;

            warpOpts->nBandCount = nBands;
            warpOpts->eResampleAlg = GRA_Bilinear;
            warpOpts->eWorkingDataType = GDT_Unknown;

            warpOpts->panSrcBands = (int*) CPLMalloc( sizeof(int) * nBands);
            for (int i=0; i<nBands; i++) warpOpts->panSrcBands[i] = i+1;
            warpOpts->panDstBands = (int*) CPLMalloc( sizeof(int) * nBands);
            for (int i=0; i<nBands; i++) warpOpts->panDstBands[i] = i+1;

            warpOpts->padfSrcNoDataReal = (double*) CPLMalloc(sizeof(double) * nBands);
            for (int i=0; i<nBands; i++) warpOpts->padfSrcNoDataReal[i] = pSrcDS->GetRasterBand(i+1)->GetNoDataValue();
            warpOpts->padfDstNoDataReal = (double*) CPLMalloc(sizeof(double) * nBands);
            for (int i=0; i<nBands; i++) warpOpts->padfDstNoDataReal[i] = dstNoData;

            warpOpts->pTransformerArg = hTransformArg;
            warpOpts->pfnTransformer = GDALGenImgProjTransform;

            GDALWarpOperation warp;
            if (warp.Initialize( warpOpts) == CE_None) {
                if (warp.ChunkAndWarpImage(0,0,nPixels,nLines) == CE_None) {
                    pDstDS->FlushCache();
                    ret = pDstDS;
                } else cerr << "failed to perform warp operation\n";
            } else cerr << "failed to initialize warp operation\n";

            GDALDestroyGenImgProjTransformer( hTransformArg);
            GDALDestroyWarpOptions(warpOpts);
            if (!ret) delete pDstDS;
        } else cerr << "failed to create output raster set\n";

    } else cerr << "failed to create output transformer\n";

    return ret;
}

bool gdalGetCenter (GDALDataset *pDS, double &longitude, double &latitude) {
    bool rc = false;

    if (pDS) {
        const char *pszPrj = pDS->GetProjectionRef();
        if (*pszPrj) { // otherwise we don't have a projectionRef
            OGRSpatialReference* pSrcSRS;
            pSrcSRS = (OGRSpatialReference*) OSRNewSpatialReference(pszPrj);
            OGRSpatialReference tgtSRS;

            tgtSRS.SetWellKnownGeogCS("EPSG:4326");
#ifdef GDAL_COMPUTE_VERSION
#if GDAL_VERSION_NUM >= GDAL_COMPUTE_VERSION(3,0,0)
            tgtSRS.SetAxisMappingStrategy(OAMS_TRADITIONAL_GIS_ORDER);
#endif /* GDAL_VERSION_NUM >= GDAL_COMPUTE_VERSION(3,0,0) */
#endif /* GDAL_COMPUTE_VERSION */

            OGRCoordinateTransformation *pCT = OGRCreateCoordinateTransformation(pSrcSRS, &tgtSRS);
            if (pCT) {
                int nX = pDS->GetRasterXSize();
                int nY = pDS->GetRasterYSize();

                double a[6];
                pDS->GetGeoTransform(a);
                double y = a[3] + a[4] * (nX / 2) + a[5] * (nY / 2);
                double x = a[0] + a[1] * (nX / 2) + a[2] * (nY / 2);

                rc = pCT->Transform(1, &x, &y);
                if (rc) {
                    longitude = x;
                    latitude = y;
                }

                OGRCoordinateTransformation::DestroyCT(pCT);
            }
        }
    }
    return rc;
}

GDALDataset* gdalWarpToUtm (const char* filename, GDALDataset* pSrcDS, const char* pszDstDriverName) {
    double lat, lon;

    if (pSrcDS && gdalGetCenter( pSrcDS, lon, lat)) {
        int utmZone = gdalGetUtmZone(lat,lon);

        GDALDriver* pDstDriver = pszDstDriverName ? GetGDALDriverManager()->GetDriverByName(pszDstDriverName) : pSrcDS->GetDriver();
        if (pDstDriver) {
            const char *pszSrcWKT = pSrcDS->GetProjectionRef();
            if (pszSrcWKT && strlen(pszSrcWKT) > 0) {
                char *pszDstWKT = NULL;

                OGRSpatialReference dstSRS;
                dstSRS.SetUTM(utmZone, lat > 0);
                dstSRS.SetWellKnownGeogCS("WGS84");
                dstSRS.exportToWkt( &pszDstWKT);

                return warp( pSrcDS, pszDstWKT, pDstDriver, filename);

            } else cerr << "no source WKT\n";
        } else cerr << "no valid driver\n";
    } else cerr << "src dataset not valid\n";
    
    return nullptr;
}

GDALDataset* gdalWarpTo4326 (const char* filename, GDALDataset* pSrcDS, const char* pszDstDriverName) {
    if (pSrcDS) {
        GDALDriver* pDstDriver = pszDstDriverName ? GetGDALDriverManager()->GetDriverByName(pszDstDriverName) : pSrcDS->GetDriver();
        if (pDstDriver) {
            const char *pszSrcWKT = pSrcDS->GetProjectionRef();
            if (pszSrcWKT && strlen(pszSrcWKT) > 0) {
                char *pszDstWKT = NULL;

                OGRSpatialReference dstSRS;
                dstSRS.SetWellKnownGeogCS("EPSG:4326");
                dstSRS.exportToWkt( &pszDstWKT);
                
                return warp( pSrcDS, pszDstWKT, pDstDriver, filename);

            } else cerr << "no source WKT\n";
        } else cerr << "no valid driver\n";
    } else cerr << "src dataset not valid\n";
    
    return nullptr;
}