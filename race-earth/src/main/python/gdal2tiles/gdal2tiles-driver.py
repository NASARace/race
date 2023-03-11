/*
 * Copyright (c) 2022, United States Government, as represented by the
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
import gdal2tiles
import sys
import os

def infer_type(arg):
    if arg == "None":
        return None
    elif arg == "false":
        return False
    elif arg == "true":
        return True
    else: # numeric
        try:
            arg = int(arg)
        except ValueError:
            pass # leave as string
        return arg

def check_dir(output_folder):
    if os.path.isdir(output_folder) == False:
        os.makedirs(output_folder)

def parse_args(args):
    kwargs = {arg.split("=")[0]:infer_type(arg.split("=")[1]) for arg in args}
    return kwargs

def create_wms_tiles(tif_path, output_folder, tile_options={}):
    check_dir(output_folder)
    gdal2tiles.generate_tiles(tif_path, output_folder, **tile_options)

if __name__ == "__main__":
    kwargs = {}
    if len(sys.argv) > 3:
        kwargs = parse_args(sys.argv[1:-2])
    create_wms_tiles(sys.argv[-2], sys.argv[-1], tile_options=kwargs)

