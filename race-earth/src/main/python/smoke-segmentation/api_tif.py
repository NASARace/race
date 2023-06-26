import zipfile
from flask import Flask, request, jsonify, abort, redirect, send_file, Response
from werkzeug.utils import secure_filename
from smoke_segmentation_functions import initiate_model, get_tif_dataset, smoke_segmentation, smoke_segmentation_png_jpg, create_wms_tiles, check_dir, convert_tif_to_image, smoke_segmentation_raw
import os
import atexit
import shutil

os.environ["USE_PATH_FOR_GDAL_PYTHON"]="YES"

def load_model():
    model_path = os.path.join(os.getcwd(), 'smoke_segmentation_512.h5')
    global segmentation_model
    segmentation_model = initiate_model(model_path)

app = Flask(__name__)
segmentation_model = None

target_shape = (512, 512)
UPLOAD_FOLDER = 'uploads'
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER
allowed_extensions = ['tif', 'jpg', 'png'] #add functionality for other filetypes

def check_file_type(filename):
    return filename.split(".")[-1].lower() in allowed_extensions 

def remove_upload_files():
    shutil.rmtree(UPLOAD_FOLDER)

@app.route("/predict", methods=["POST", "GET"])
def predict():
    data = {"success": False}
    if request.method == 'GET':
        response = Response(status=200) 
        return response   
    elif request.method == 'POST':
        # check if the post request has the file part
        if 'file' not in request.files:
            print('No file part, using data')
            with open(os.path.join(UPLOAD_FOLDER, "temp.tif"), "wb") as f:
                f.write(request.data)
            file_path = os.path.join(UPLOAD_FOLDER, "temp.tif")
            #return redirect(request.url)
        else:
            file = request.files['file']
            filename = file.filename
            filename = secure_filename(filename)
            file_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)
            file.save(file_path)
        if check_file_type(file_path):
            if ".tif" in file_path:
                tif_dataset = get_tif_dataset(file_path)
                output_path =  file_path.replace(".tif", "_segmented.tif")
                output, output_paths = smoke_segmentation_raw(tif_dataset, segmentation_model, target_shape, output_path, tile=True, overlap=48)

                del tif_dataset #closes original tiff
            else:
                output_path =  file_path.replace(".", "_segmented.")
                output = smoke_segmentation_png_jpg(file_path, segmentation_model, target_shape, output_path, tile=True)
                output_paths = [output_path]

            if len(output_paths)>1: #create zip
                zipf = zipfile.ZipFile('SCS.zip','w', zipfile.ZIP_DEFLATED)
                for root, dirs, files in os.walk(app.config['UPLOAD_FOLDER']):
                    for file in files:
                        zipf.write(os.path.join(app.config['UPLOAD_FOLDER'],file))
                zipf.close()
                return send_file('SCS.zip',
                        mimetype = 'zip',
                        as_attachment = True)
        
            data["success"] = True
            data["output"] = output_paths
            print("Ran Prediction")
            return send_file(output_paths[0], mimetype='image/tiff', as_attachment=True)
    else: #input file is not a tif, jpg, or png
        abort(400) 
    return jsonify(data)
    

if __name__ == "__main__":
    print(("* Loading Keras model, checking upload folder, and Flask starting server..."
        "please wait until server has fully started"))
    #load model
    load_model()
    #check upload folder exits
    check_dir(UPLOAD_FOLDER)
    atexit.register(remove_upload_files)
    app.run()