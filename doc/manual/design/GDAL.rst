GDAL
====

GDAL (https://gdal.org/index.html) is a standard library for working with georeferenced raster and vector files, such as geotiffs and netcdfs. 
Race provides wrappers for native GDAL executables to enable GDAL functions from a scala programming interface. 
Since GDAL performs best when the native libraries are installed according to the operating system, RACE implementations
wrappers to the commandline executables rather than a scala/java library. The wrappers provided in RACE use plain 
english function names which correspond to specific executable commands.

Requirements
------------

* exisiting gdal installation (https://gdal.org/download.html). If on Windows, we recommend installing via Anaconda in a dedicated environment


  ex: 
  `conda create -n gdal`
  `conda activate gdal`
  `conda install -c conda-forge gdal`

gdalinfo
--------

gdalinfo extracts information about a specified file, including the georeferenced coordinate system.

gdalwarp
--------

gdalwarp provides methods for modifying (warping) images, including reprojection and cropping.

**Usage**:

1. To use in scala, first create a new gdalwarp instance of the gov.nasa.race.earth.Gdal2Tiles class 
by specifying the driver path, python path, input file, and output path:
    
.. code:: scala
    val warpProg = new File("C:/mypath/gdalwarp.exe")
    val inFile = new File("myinput.tif")
    val outFile = new File("myoutput.tif")
    val warp = new GdalWarp(prog = warpProg, inFile = inFile, outFile = outFile)

2. Add any commands to the GdalWarp instance:
.. code:: scala
    val xMin = "-127.71"
    val yMin = "34.91"
    val xMax = "-115.79"
    val yMax = "41.25"
    warp.setTargetBounds( xMin, yMin, xMax, yMax)

2. Execute the commands, remember the output is a future:

.. code:: scala
    val warpFuture = warp.exec
    warpFuture.onComplete{
        case Success(v) => println(v)
        case Failure(e) => e.printStackTrace
    }

gdaltranslate
-------------

gdaltranslate provides methods for translating vector images to raster images (e.g., netcdf to geotiff).
Usage is similar to GdalWarp.

gdal2tiles
----------

The gdal2tiles-driver.py file allows scala applications (e.g., RACE-ODIN applications) to use the python gdal2tiles packages. 
The core functionality of this pacakge is to reformat geotiffs into WMS tiles for data display.

**Additional Requirements**:

1. existing python installation (.exe) 
2. gdal2tiles python package (https://gdal2tiles.readthedocs.io/en/latest/installation.html) installed in python environment which runs the exisiting executable from 1 (pip recommended) and has gdal installed

    ex: 
    `conda activate gdal`
    `pip install gdal2tiles`

**Usage**:

1. To use in scala, first create a new gdal2tiles instance of the gov.nasa.race.earth.Gdal2Tiles class 
by specifying the driver path, python path, input file, and output path:
    
.. code:: scala
    val tilesProg = new File("C:/mypath/Anaconda3/python.exe")
    val outputPath = new File("tiles")
    val driverPath = new File("C:/mypath/gdal2tiles-driver.py")
    val tiles = new Gdal2Tiles(prog = tilesprog, inFile = inFile, outputPath = outputPath, driverPath = driverPath)

Note that the driverPath is in ".../race/race-earth/src/main/python/gdal2tiles/gdal2tiles-driver.py"

2. Execute the new Gdal2Tiles instance to produce tiles, remember the output is a future:

.. code:: scala
    val tilesFuture = tiles.exec
    tilesFuture.onComplete{
        case Success(v) => println(v)
        case Failure(e) => e.printStackTrace
    }
    Await.ready(tilesFuture, 60.seconds)

