#include <gdal_utils.h>
#include <gdal_alg.h>
#include <ogr_api.h>
#include <ogr_srs_api.h>
#include <gdalwarper.h>
#include <cpl_conv.h>
#include <cpl_error.h>

#include <stdio.h> // for debugging

// another un-exported function from gdalwarpsimple
// note this does allocate a new string that has to be freed by the caller with CPLFree(pszResult)
char* SanitizeSRS (const char *pszUserInput) {
    char *pszResult = NULL;
    OGRSpatialReferenceH hSRS = OSRNewSpatialReference(NULL);

    CPLErrorReset();
    if (OSRSetFromUserInput(hSRS, pszUserInput) == OGRERR_NONE) {
        OSRExportToWkt(hSRS, &pszResult);
    }

    OSRDestroySpatialReference(hSRS);

    return pszResult;
}

// this is a close copy of the respective function in gdalwarpsimple
// we could do this in Rust but constant unsafe{} and error translation would make this less readable
GDALDatasetH GDALWarpCreateOutput( GDALDatasetH hSrcDS,
                                   const char *pszFilename,
                                   const char *pszFormat,
                                   char* pszSourceSRS,
                                   char* pszTargetSRS,
                                   char **papszCreateOptions,
                                   double dfMinX, double dfMaxX, double dfMinY, double dfMaxY,
                                   double dfXRes, double dfYRes,
                                   int nForcePixels, int nForceLines
                                 ) {
    GDALDatasetH hDstDS = NULL;
    void *hTransformArg = NULL;
    double adfDstGeoTransform[6];
    int nPixels = 0, nLines = 0;

    GDALDriverH hDriver = GDALGetDriverByName(pszFormat);
    if (hDriver == NULL) return NULL;

    hTransformArg = GDALCreateGenImgProjTransformer( hSrcDS, pszSourceSRS, NULL, pszTargetSRS, FALSE, 0.0, 0);
    if (hTransformArg == NULL) goto exit;

    // get approximate output definition
    if (GDALSuggestedWarpOutput( hSrcDS, GDALGenImgProjTransform, hTransformArg,
                                 adfDstGeoTransform, &nPixels, &nLines) != CE_None) goto exit;

    //--- did user override parameters?
    if (dfXRes != 0.0 && dfYRes != 0.0) { // given pixel resolutions
        CPLAssert(nPixels == 0 && nLines == 0);
        if (dfMinX == 0.0 && dfMinY == 0.0 && dfMaxX == 0.0 && dfMaxY == 0.0) {
            dfMinX = adfDstGeoTransform[0];
            dfMaxX = adfDstGeoTransform[0] + adfDstGeoTransform[1] * nPixels; // ?? nPixels == 0
            dfMaxY = adfDstGeoTransform[3];
            dfMinY = adfDstGeoTransform[3] + adfDstGeoTransform[5] * nLines;
        }

        nPixels = (int)((dfMaxX - dfMinX + (dfXRes / 2.0)) / dfXRes);
        nLines = (int)((dfMaxY - dfMinY + (dfYRes / 2.0)) / dfYRes);
        adfDstGeoTransform[0] = dfMinX;
        adfDstGeoTransform[3] = dfMaxY;
        adfDstGeoTransform[1] = dfXRes;
        adfDstGeoTransform[5] = -dfYRes;

    } else if (nForcePixels != 0 && nForceLines != 0) {  // given nPixels/nLines
        if (dfMinX == 0.0 && dfMinY == 0.0 && dfMaxX == 0.0 && dfMaxY == 0.0) {
            dfMinX = adfDstGeoTransform[0];
            dfMaxX = adfDstGeoTransform[0] + adfDstGeoTransform[1] * nPixels;
            dfMaxY = adfDstGeoTransform[3];
            dfMinY = adfDstGeoTransform[3] + adfDstGeoTransform[5] * nLines;
        }

        dfXRes = (dfMaxX - dfMinX) / nForcePixels;
        dfYRes = (dfMaxY - dfMinY) / nForceLines;

        adfDstGeoTransform[0] = dfMinX;
        adfDstGeoTransform[3] = dfMaxY;
        adfDstGeoTransform[1] = dfXRes;
        adfDstGeoTransform[5] = -dfYRes;

        nPixels = nForcePixels;
        nLines = nForceLines;

    } else if (dfMinX != 0.0 || dfMinY != 0.0 || dfMaxX != 0.0 || dfMaxY != 0.0) { // given bbox
        dfXRes = adfDstGeoTransform[1];
        dfYRes = fabs(adfDstGeoTransform[5]);

        nPixels = (int)((dfMaxX - dfMinX + (dfXRes / 2.0)) / dfXRes);
        nLines = (int)((dfMaxY - dfMinY + (dfYRes / 2.0)) / dfYRes);

        adfDstGeoTransform[0] = dfMinX;
        adfDstGeoTransform[3] = dfMaxY;
    }

    int nBands = GDALGetRasterCount(hSrcDS);
    hDstDS = GDALCreate(hDriver, pszFilename, nPixels, nLines,
                        nBands, GDALGetRasterDataType(GDALGetRasterBand(hSrcDS, 1)), papszCreateOptions);

    if (hDstDS != NULL) {
      GDALSetProjection(hDstDS, pszTargetSRS);
      GDALSetGeoTransform(hDstDS, adfDstGeoTransform);

      // preserve no-data values
      for (int i=1; i<=nBands; i++) { // it's 1-based
         GDALRasterBandH hSrcBand = GDALGetRasterBand(hSrcDS, i);
         double nv = GDALGetRasterNoDataValue( hSrcBand, NULL);

         GDALRasterBandH hDstBand = GDALGetRasterBand( hDstDS, i);
         GDALSetRasterNoDataValue(hDstBand, nv);

         GDALColorTableH hCT = GDALGetRasterColorTable(hSrcBand);
         if (hCT != NULL) {
           GDALSetRasterColorTable(hDstBand, hCT);
         }
      }
    }

    exit:
    if (hTransformArg) GDALDestroyGenImgProjTransformer(hTransformArg);

    return hDstDS;
}

CPLErr ChunkAndWarp ( GDALDatasetH hSrcDS, GDALDatasetH hDstDS, double dfMaxError) {
    GDALWarpOptions *psWarpOptions = GDALCreateWarpOptions();
    psWarpOptions->hSrcDS = hSrcDS;
    psWarpOptions->hDstDS = hDstDS;

    psWarpOptions->nBandCount = GDALGetRasterCount(hSrcDS);
    int* srcBands = (int *) CPLMalloc(sizeof(int) * psWarpOptions->nBandCount );
    int* dstBands = (int *) CPLMalloc(sizeof(int) * psWarpOptions->nBandCount );
    for (int i=0; i<psWarpOptions->nBandCount; i++) {
        srcBands[i] = i+1;
        dstBands[i] = i+1;
    }
    psWarpOptions->panSrcBands = srcBands;
    psWarpOptions->panDstBands = dstBands;

    psWarpOptions->pfnProgress = GDALDummyProgress;
    //psWarpOptions->dfWarpMemoryLimit = 256.0 * 1024 * 1024; // should be based on available memory (default 64k)

    void* pGenImgTransformerArg =  GDALCreateGenImgProjTransformer( hSrcDS,
                                                             GDALGetProjectionRef(hSrcDS),
                                                             hDstDS,
                                                             GDALGetProjectionRef(hDstDS),
                                                             FALSE, 0.0, 0 );
    void* pTransformerArg = pGenImgTransformerArg;
    GDALTransformerFunc pfnTransformer = GDALGenImgProjTransform;

    void* pApproxTransformerArg = NULL;
    if (dfMaxError > 0.0) {
        pApproxTransformerArg = GDALCreateApproxTransformer( GDALGenImgProjTransform, pTransformerArg, dfMaxError);
        pTransformerArg = pApproxTransformerArg;
        pfnTransformer = GDALApproxTransform;
    }

    psWarpOptions->pTransformerArg = pTransformerArg;
    psWarpOptions->pfnTransformer = pfnTransformer;

    CPLErr res = CE_Failure;
    GDALWarpOperationH pWarpOp = GDALCreateWarpOperation(psWarpOptions);
    if (pWarpOp) {
        res = GDALChunkAndWarpImage( pWarpOp, 0,0, GDALGetRasterXSize(hDstDS), GDALGetRasterYSize(hDstDS));
        GDALDestroyWarpOperation(pWarpOp); // also destroys psWarpOptions

    } else {
        GDALDestroyWarpOptions( psWarpOptions );
    }

    // those have to be released explicitly
    GDALDestroyGenImgProjTransformer( pGenImgTransformerArg);
    if (pApproxTransformerArg) GDALDestroyApproxTransformer(pApproxTransformerArg);

    return res;
}
