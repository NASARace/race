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
#include <iostream>
#include <cstdio>
#include <gdal.h>
#include <gdal_priv.h>

#include "gdal_utils.h"
#include "arg_opts.h"

using std::cerr, std::cout, std::endl, std::string;

static void printCsvGrid (VSILFILE* fout, GDALDataset* pDS) {
    int nCols = pDS->GetRasterXSize();
    int nRows = pDS->GetRasterYSize();

    double a[6];
    if (pDS->GetGeoTransform(a) != CE_None){
        cerr << "error retrieving HUVW geo transform\n";
        return;
    }

    double x0 = a[0];
    double cx = a[1];
    double y0 = a[3];
    double cy = a[5];

    double cx2 = a[1] / 2;
    double cy2 = a[5] / 2;

    std::unique_ptr<float[]> hs(new float[nCols]), us(new float[nCols]), vs(new float[nCols]), ws(new float[nCols]);

    GDALRasterBand* pH = pDS->GetRasterBand(1);
    GDALRasterBand* pU = pDS->GetRasterBand(2);
    GDALRasterBand* pV = pDS->GetRasterBand(3);
    GDALRasterBand* pW = pDS->GetRasterBand(4);

    VSIFPrintfL(fout, "# nx:%d, x0:%f, dx:%f, ny:%d, y0:%f, dy:%f\n", nCols, x0, cx, nRows, y0, cy);
    VSIFPrintfL(fout, "h, u,v,w, spd m/sec\n");

    for (int i=0; i<nRows; i++) {
        if ((pH->RasterIO(GF_Read, 0, i, nCols,1, hs.get(), nCols,1,GDT_Float32, 0,0,nullptr ) != CE_None)
             || (pV->RasterIO(GF_Read, 0, i, nCols,1, vs.get(), nCols,1,GDT_Float32, 0,0,nullptr ) != CE_None)
             || (pW->RasterIO(GF_Read, 0, i, nCols,1, ws.get(), nCols,1,GDT_Float32, 0,0,nullptr ) != CE_None)
             || (pU->RasterIO(GF_Read, 0, i, nCols,1, us.get(), nCols,1,GDT_Float32, 0,0,nullptr ) != CE_None) ) {
            cerr << "error reading HUVW grid line " << i << endl;
            return;
        }

        for (int j=0; j<nCols; j++) {
            float u = us[j];
            float v = vs[j];
            float w = ws[j];
            float spd = std::sqrt( u*u + v*v + w*w);

            VSIFPrintfL(fout, "%.1f,%.1f,%.1f,%.1f,%.1f\n", hs[j], u, v, w, spd);
        }
    }
}

string tmpName (const char* base, const char* suffix) {
    string fname = base;
    fname += suffix;
    return fname;
}

/**
 * convert a 5-band HUVW GDAL dataset containing elevation and u,v,w vector grid data into
 * a CSV file with following structure:
 * 
 *   # nx:311, x0:-122.679394, dx:0.002630, ny:182, y0:37.479012, dy:-0.002630
 *   h, u,v,w, spd m/sec
 *   6.1,-0.4,-2.3,-0.0,2.4
 *   ...
 * 
 * line 1 is a comment line with grid information
 *              nx: number of grid columns
 *              x0: left (west) boundary of grid
 *              dx: x increment between columns
 *              ny: number of grid rows
 *              y0: upper (north) boundary of grid
 *              dy: y increment between rows
 * line 2 holds the header for successive vector data
 * line 3..N hold (h,u,v,w,speed) values (h in [m], vector components in [m/sec]) 
 */
int main (int argc, char **argv) {
    arg_opts opts("usage: huvw_csv_grid [-z] <in-filename> <out-filename>");
    if (!opts.parse(argc,argv)) return 1;
    string outputFile = opts.expanded_outputFile();

    int ret = 1;

    GDALAllRegister();

    GDALDataset *pDS = GDALDataset::FromHandle(GDALOpen(opts.inputFile, GA_ReadOnly));
    if (pDS) {
        string warpedName = tmpName(opts.outputFile, "-4326");
        GDALDataset *pWarpedDS = gdalWarpTo4326( warpedName.c_str(), pDS);
        if (pWarpedDS) {
            string croppedName = tmpName(opts.outputFile, "-cropped");
            GDALDataset *pCroppedDS = gdalCropToData( croppedName.c_str(), pWarpedDS, 0.1);
            pWarpedDS->ReleaseRef();
            VSIUnlink(warpedName.c_str());

            if (pCroppedDS) {
                VSILFILE *fout = VSIFOpenL( outputFile.c_str(), "w");
                if (fout) {
                    printCsvGrid( fout, pCroppedDS);
                    VSIFCloseL(fout);
                    cout << "CSV grid output written to " << outputFile << endl;
                    ret = 0;

                } else cerr << "failed to open output file " << outputFile << endl;
                pCroppedDS->ReleaseRef();
                VSIUnlink(croppedName.c_str());

            } else  cerr << "failed to crop " << warpedName << " to defined data rectangle\n";
        } else cerr << "failed to warp " << opts.inputFile << " to epsg:4326 (lon/lat)\n";
    } else  cerr <<  "failed to open input dataset " << opts.inputFile << endl;

    return ret;
}