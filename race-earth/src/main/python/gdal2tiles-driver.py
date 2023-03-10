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

