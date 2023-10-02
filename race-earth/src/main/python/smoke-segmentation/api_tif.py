import zipfile
from flask import Flask, request, jsonify, abort, redirect, send_file, Response
from werkzeug.utils import secure_filename
from gevent.pywsgi import WSGIServer
from waitress import serve
from smoke_segmentation_functions import initiate_model, get_tif_dataset, smoke_segmentation, smoke_segmentation_png_jpg, create_wms_tiles, check_dir, convert_tif_to_image, smoke_segmentation_raw
import os
import atexit
import shutil
import signal
import uuid
import logging

logging.basicConfig(filename='example.log', 
                    encoding='utf-8', 
                    level=logging.DEBUG,
                    datefmt='%m/%d/%Y %I:%M:%S %p')

logger = logging.getLogger('waitress')
logger.setLevel(logging.INFO)


os.environ["USE_PATH_FOR_GDAL_PYTHON"]="YES"

app = Flask(__name__)

def load_model():
    model_path = os.path.join(os.getcwd(), 'smoke_segmentation_512.h5')
    global segmentation_model
    segmentation_model = initiate_model(model_path)


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
            file_path = os.path.join(UPLOAD_FOLDER, str(uuid.uuid1())+"_temp.tif")
            with open(file_path, "wb") as f:
                f.write(request.data)
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
                output = smoke_segmentation_raw(tif_dataset, segmentation_model, target_shape, output_path, tile=True, overlap=48)

                del tif_dataset #closes original tiff
                del output #closes new tiff
            else:
                output_path =  file_path.replace(".", "_segmented.")
                output = smoke_segmentation_png_jpg(file_path, segmentation_model, target_shape, output_path, tile=True)
                output_path = [output_path]

            if type(output_path)==list: #create zip
                zipf = zipfile.ZipFile('SCS.zip','w', zipfile.ZIP_DEFLATED)
                for root, dirs, files in os.walk(app.config['UPLOAD_FOLDER']):
                    for file in files:
                        zipf.write(os.path.join(app.config['UPLOAD_FOLDER'],file))
                zipf.close()
                return send_file('SCS.zip',
                        mimetype = 'zip',
                        as_attachment = True)
        
            data["success"] = True
            data["output"] = output_path
            return send_file(output_path, mimetype='image/tiff', as_attachment=True)
    else: #input file is not a tif, jpg, or png
        abort(400) 
    return jsonify(data)

@app.route('/stop_server', methods=['GET'])
def stopServer():
    remove_upload_files()
    os.kill(os.getpid(), signal.SIGINT)
    return jsonify({ "success": True, "message": "Server has been shut down" })
    

if __name__ == "__main__":
    print(("* Loading Keras model, checking upload folder, and Flask starting server..."
        "please wait until server has fully started"))
    #load model
    load_model()
    #check upload folder exits
    check_dir(UPLOAD_FOLDER)
    #app.run()
    serve(app, host="0.0.0.0", port=5000)
    # http_server = WSGIServer(('', 5000), app)
    # http_server.serve_forever()