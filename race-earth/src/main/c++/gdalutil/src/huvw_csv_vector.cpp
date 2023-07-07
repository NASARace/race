#include <iostream>
#include <gdal.h>
#include <gdal_priv.h>

#include "arg_opts.h"

using std::cerr, std::cout, std::endl, std::string;

typedef bool (*pv_func_t)(VSILFILE* fout, OGRCoordinateTransformation*,bool,double,double,double,float,float,float,float);

static void printVectors (VSILFILE* fout, GDALDataset* pDS, int tgtEpsg, pv_func_t pv) {
    if (pDS->GetRasterCount() < 4) {
        cerr << "invalid HUVW dataset (wrong raster count)\n";
        return;
    }

    const OGRSpatialReference *srcSRS = pDS->GetSpatialRef();
    OGRSpatialReference tgtSRS;
    tgtSRS.importFromEPSG( tgtEpsg);

    if (!srcSRS) {
        cerr << "no SRS in HUVW dataset\n";
        return;
    }

    OGRCoordinateTransformation* pTrans = OGRCreateCoordinateTransformation( srcSRS, &tgtSRS);
    if (!pTrans) {
        cerr << "failed to create coordinate transformation for HUVW dataset\n";
        return;
    }

    int nCols = pDS->GetRasterXSize();
    int nRows = pDS->GetRasterYSize();

    double a[6];
    if (pDS->GetGeoTransform(a) != CE_None){
        OCTDestroyCoordinateTransformation( pTrans);
        cerr << "error retrieving HUVW geo transform\n";
        return;
    }
    double cellSize = a[1]; // we assume a[1] == a[5] for HUVW dataset
    std::unique_ptr<float[]> h(new float[nCols]), u(new float[nCols]), v(new float[nCols]), w(new float[nCols]);

    GDALRasterBand* pH = pDS->GetRasterBand(1);
    GDALRasterBand* pU = pDS->GetRasterBand(2);
    GDALRasterBand* pV = pDS->GetRasterBand(3);
    GDALRasterBand* pW = pDS->GetRasterBand(4);

    double cx2 = a[1] / 2;
    double cy2 = a[5] / 2;

    for (int i=0; i<nRows; i++) {
        if ((pH->RasterIO(GF_Read, 0, i, nCols,1, h.get(), nCols,1,GDT_Float32, 0,0,nullptr ) != CE_None)
             || (pU->RasterIO(GF_Read, 0, i, nCols,1, u.get(), nCols,1,GDT_Float32, 0,0,nullptr ) != CE_None)
             || (pV->RasterIO(GF_Read, 0, i, nCols,1, v.get(), nCols,1,GDT_Float32, 0,0,nullptr ) != CE_None)
             || (pW->RasterIO(GF_Read, 0, i, nCols,1, w.get(), nCols,1,GDT_Float32, 0,0,nullptr ) != CE_None) ) {
            cerr << "error reading HUVW grid line " << i << "\n";
            return;
        }

        for (int j=0; j<nCols; j++) {
            // the grid values are for the respective grid cell centers. note there is no rotation
            double x = a[0] + (a[1] * j) + cx2;
            double y = a[3] + (a[5] * i) + cy2;

            if (!pv( fout, pTrans, (i==0 && j==0), cellSize, x, y, h[j], u[j], v[j], w[j])){
                return;
            }
        }
    }
}

// returns length of wind vector in percent of cell size
static double scaleFactor (float u, float v, float w, double& spd)
{
    //spd = std::sqrt( u*u + v*v + w*w);
    spd = std::sqrt( u*u + v*v);
    // we could emphasize high vertical (w) wind components here to avoid ortho view angle distortion

    if (spd < 2.2352) return 0.2;  // < 5mph
    if (spd < 4.4704) return 0.4;  // < 10mph
    if (spd < 8.9408) return 0.6;  // < 20mph
    return 0.8; // >= 20mph
}

static bool printCsvVector (VSILFILE* fout, OGRCoordinateTransformation* pTrans, bool isFirst, 
                            double cellSize, double x, double y, float h, float u, float v, float w)
{
    double spd = 0; // [m/sec]
    double s = scaleFactor(u,v,w, spd) * cellSize; // length of display vector in [m]

    double f = s / spd;
    double su = u * f;
    double sv = v * f;
    double sw = w * f;

    double xs[] = { x, x + su };
    double ys[] = { y, y + sv };
    double zs[] = { h, h + sw };

    //double spdMph = spd * 2.23693629; // m/sec -> mph

    if (pTrans->Transform( 2, xs, ys, zs, nullptr)) {
        VSIFPrintfL(fout, "%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.2f\n", xs[0], ys[0], zs[0], xs[1], ys[1], zs[1], spd);
        return true;
    } else {
        cerr << "HUVW coordinate transformation failed\n";
        return false;
    }
}

static void printCsvVectors (VSILFILE* fout, GDALDataset* pDS) {
    int nCols = pDS->GetRasterXSize();
    int nRows = pDS->GetRasterYSize();

    VSIFPrintfL(fout, "# length:%d\n", nCols*nRows); // comment prefix line to let clients pre-allocate data
    VSIFPrintfL(fout, "x0,y0,z0, x1,y1,z1, spd m/sec\n");

    printVectors(fout,pDS, 4978, printCsvVector); // epsg:4989 is ECEF
}

/**
 * generate CSV ECEF vector data file from HUVW GDAL data set
 * use: huvw_csv_vector [-z] inputFile outputFile
 * 
 * output format:
 * 
 *   # length:62780
 *   x0,y0,z0, x1,y1,z1, spd m/sec
 *   -2736099.0,-4265420.2,3860083.1,-2736149.6,-4265458.3,3860005.7,2.36
 *   ...
 * 
 * line 1 holds a comment with the number of vectors in the file
 * line 2 is the header line for the data
 * line 3..N are the data lines holding the ECEF vectors
 */
int main (int argc, char **argv) {

    arg_opts opts("usage: huvw_csv_vector [-z] <in-filename> <out-filename>");
    if (!opts.parse(argc,argv)) return 1;
    string outputFile = opts.expanded_outputFile();

    GDALAllRegister();

    GDALDataset *pDS = GDALDataset::FromHandle(GDALOpen(opts.inputFile, GA_ReadOnly));
    if (pDS) {
        VSILFILE *fout = VSIFOpenL( outputFile.c_str(), "w");
        if (fout) {
            printCsvVectors( fout, pDS);
            VSIFCloseL(fout);
            cout << "CSV vector output written to " << outputFile << endl;
            return 0;

        } else {
            cerr << "failed to open output file " << outputFile << endl;
            return 1;
        }

    } else {
        cerr <<  "failed to open input dataset " << opts.inputFile << endl;
        return 1;
    }
}