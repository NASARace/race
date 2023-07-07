
#add_executable(cropdata cropdata.cpp)
add_executable(huvw_csv_vector huvw_csv_vector.cpp arg_opts.cpp)
add_executable(huvw_csv_grid huvw_csv_grid.cpp gdal_utils.cpp arg_opts.cpp)

include(FindGDAL)
find_package(GDAL REQUIRED)

target_include_directories(huvw_csv_grid PRIVATE ${GDAL_INCLUDE_DIR})
target_include_directories(huvw_csv_vector PRIVATE ${GDAL_INCLUDE_DIR})

#target_link_libraries(cropdata PRIVATE ${GDAL_LIBRARY})
target_link_libraries(huvw_csv_vector PRIVATE ${GDAL_LIBRARY})
target_link_libraries(huvw_csv_grid PRIVATE ${GDAL_LIBRARY})