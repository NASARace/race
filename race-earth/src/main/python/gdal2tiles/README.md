# gdal2tiles driver docs

## Description:

The gdal2tiles-driver.py file allows scala applications (e.g., RACE-ODIN applications) to use the python gdal2tiles packages. The core functionality of this pacakge is to reformat geotiffs into WMS tiles for data display.

## Requirements:
- exisiting gdal installation (https://gdal.org/download.html). If on Windows, we recommend installing via Anaconda

  ex: `conda install -c conda-forge gdal`
- existing python installation (.exe)
- gdal2tiles python package (https://gdal2tiles.readthedocs.io/en/latest/installation.html) installed in default python environment (pip recommended)

    ex: `pip install gdal2tiles`

## Usage:
- Use in scala, create a new gdal2tiles instance of the gov.nasa.race.earth.Gdal2Tiles class by passing in the driver path, python path, input file, and output path:
    
```
   val tilesProg = new File("C:/mypath/Anaconda3/python.exe")
   val outputPath = new File("tiles")
   val driverPath = new File("C:/mypath/gdal2tiles-driver.py")
   val tiles = new Gdal2Tiles(prog = tilesprog, inFile = inFile, outputPath = outputPath, driverPath = driverPath)
  ```

Note that the driverPath is in ".../race/race-earth/src/main/python"
- execute the new Gdal2Tiles instance to produce tiles, remember the output is a future:
```
 val tilesFuture = tiles.exec
 tilesFuture.onComplete{
    case Success(v) => println(v)
    case Failure(e) => e.printStackTrace
 }
 Await.ready(tilesFuture, 60.seconds)
 ```
