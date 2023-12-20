GDAL-based utilities to translate geotiff huvw (height,u-/v-/w-wind component) datasets into CSV

from gdalutil directory do:

mkdir build
cd build

cmake -DCMAKE_BUILD_TYPE=Release ..
cmake --build . --target clean
cmake --build .
src/huvw_csv_grid

then add links for huvw_csv_grid and huvw_csv_vector to configured RACE "executable-paths" directory
