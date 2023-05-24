from flask import Flask, request, jsonify, abort, Response
from smoke_segmentation_functions import initiate_model, get_tif_dataset, smoke_segmentation, smoke_segmentation_png_jpg, create_wms_tiles
import os
import time

os.environ["USE_PATH_FOR_GDAL_PYTHON"]="YES"

def load_model():
    model_path = os.path.join(os.getcwd(), 'smoke_segmentation_512.h5')
    global segmentation_model
    segmentation_model = initiate_model(model_path)

app = Flask(__name__)
segmentation_model = None

target_shape = (512, 512)
allowed_extensions = ['tif', 'jpg', 'png'] #add functionality for other filetypes


def check_file_type(filename):
    return filename.split(".")[-1].lower() in allowed_extensions 

@app.route("/predict", methods=["POST", "GET"])
def predict():
    data = {}
    if request.method == 'POST':
        start = time.time()
        try:
            input = request.get_json()
            file = input['file']
        except: # handles requests without json header/incorrect mime type
            input = request.get_data().decode('utf-8')
            input = dict((a.strip("{}").strip('"'), b.strip("{}").strip('"'))  
                     for a, b in (element.split('":"') 
                                  for element in input.split(', ')))  
        file = input['file']
        #default values
        tile = True
        wms_tile = False
        separate_layers = True
        if "tile" in input:
            tile = input["tile"]
        if "wms_tile" in input:
            wms_tile = input["wms_tile"]
        if "separate_layers" in input:
            separate_layers = input["separate_layers"]
        if check_file_type(file) == True:
            if ".tif" in file:
                tif_dataset = get_tif_dataset(file)
                output_path = file.replace(".tif", "_segmented.tif")
                output, output_paths = smoke_segmentation(tif_dataset, segmentation_model, target_shape, output_path, tile=tile, plot=False, separate_layers=separate_layers)
                if wms_tile == True:
                    for tif_output_path in output_paths:
                        output_type = tif_output_path.split("_")[-1].strip(".tif")
                        output_folder = os.path.dirname(output_path)
                        output_folder = os.path.join(output_folder,"wms_smoke_tiles_"+output_type)
                        create_wms_tiles(tif_output_path, output_folder, tile_options={})
                        data["wms_output"] = output_folder
                del output #closes tiff
                del tif_dataset #closes tiff
            else:
                output_path = file.replace(".", "_segmented.")
                output = smoke_segmentation_png_jpg(file, segmentation_model, target_shape, output_path, tile=tile, plot=False)
                output_paths = [output_path]
            
            data["success"] = True
            data["output"] = output_paths
        else: #input file is not a tif, jpg, or png
            abort(400) 
    elif request.method == 'GET':
        response = Response(status=200) 
        return response       
    return jsonify(data)
    

if __name__ == "__main__":
    print(("* Loading Keras model and Flask starting server..."
        "please wait until server has fully started"))
    #load model
    load_model()
    app.run()