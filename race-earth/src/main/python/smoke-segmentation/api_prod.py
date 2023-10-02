import zipfile
import tensorflow as tf
from flask import Flask, request, jsonify, abort, redirect, send_file, Response
from werkzeug.utils import secure_filename
from gevent.pywsgi import WSGIServer
from waitress import serve
from smoke_segmentation_functions import initiate_model, get_tif_dataset, check_dir, convert_tif_to_image, smoke_segmentation_raw, get_tiles, DataLoader, process_data, reconstruct_image, create_mask, get_segmeneted_mutiband_tif
import os
import atexit
import shutil
import signal
import uuid
import logging
import time
import numpy as np

os.environ["USE_PATH_FOR_GDAL_PYTHON"]="YES"

#logging set up
logger = logging.getLogger('waitress')

#formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
console = logging.StreamHandler()
console.setLevel(logging.ERROR)
#console.setFormatter(formatter)

file = logging.FileHandler('smoke-segmentation.log')
file.setLevel(logging.DEBUG)
#file.setFormatter(formatter)

logging.basicConfig(level=logging.DEBUG,
                    format='%(asctime)s %(levelname)s %(module)s %(funcName)s %(message)s',
                    handlers=[console, file]
                    )
# variable def

app = Flask(__name__)

segmentation_model = None
target_shape = (512, 512)
UPLOAD_FOLDER = 'uploads'
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER
allowed_extensions = ['tif']

# functions
def check_file_type(filename):
    return filename.split(".")[-1].lower() in allowed_extensions 

def remove_upload_files():
    shutil.rmtree(UPLOAD_FOLDER)

def load_model():
    model_path = os.path.join(os.getcwd(), 'smoke_segmentation_512.h5')
    global segmentation_model
    segmentation_model = initiate_model(model_path)

# route definition

@app.route("/predict", methods=["POST", "GET"])
def predict():
    data = {"success": False}
    if request.method == 'GET':
        response = Response(status=200) 
        return response   
    elif request.method == 'POST':
        start = time.time()
        # check if the post request has the file part
        if 'file' not in request.files:
            file_path = os.path.join(UPLOAD_FOLDER, str(uuid.uuid1())+"_temp.tif")
            with open(file_path, "wb") as f:
                f.write(request.data)
            file_write = time.time()
        else:
            file = request.files['file']
            filename = file.filename
            filename = secure_filename(filename)
            file_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)
            file.save(file_path)
            file_write = time.time()
        logger.info("file import time.time(): %f", file_write-start)
            
        
        if check_file_type(file_path):
            # open tif
            tif_dataset = get_tif_dataset(file_path)
            tif_open = time.time()
            logger.info("tif opening time.time(): %f", tif_open-file_write)
            # convert to image
            image = convert_tif_to_image(tif_dataset)
            tif_to_image = time.time()
            logger.info("tif to image time.time(): %f", tif_to_image-tif_open)
            # process tif into tiles
            images, full_tiled_shape, padding_tuple, overlap = get_tiles(image, target_shape, overlap=48)
            tif_tile = time.time()
            logger.info("image tiling time.time(): %f", tif_tile-tif_to_image)
            # format images
            inputs = tf.convert_to_tensor(np.asarray(images))#tf.image.resize(images, target_shape)
            inputs_tf = tf.data.Dataset.from_tensor_slices((inputs))
            inputs_tf_batch = inputs_tf.batch(32)
            format_time = time.time()
            logger.info("image formatting time.time(): %f", format_time-tif_tile)
            # run segmentation
            logger.info( inputs_tf_batch)
            pred = segmentation_model.predict( inputs_tf_batch)
            pred_time = time.time()
            logger.info("segmentation time.time(): %f", pred_time-format_time)
            # reconstruction
            pred_reshaped = reconstruct_image(pred, full_tiled_shape, padding_tuple, target_shape, overlap=overlap)
            reconstruction_time = time.time()
            logger.info("reconstruction time.time(): %f", reconstruction_time-pred_time)
            # mask and final output
            pred_mask = create_mask(pred_reshaped)
            output_path =  file_path.replace(".tif", "_segmented.tif")
            output = get_segmeneted_mutiband_tif(tif_dataset, pred_mask, output_path)
            output_time = time.time()
            logger.info("output time.time(): %f", output_time-reconstruction_time)
            # clear memory
            del tif_dataset #closes original tiff
            del output #closes new tiff
            # response
            data["success"] = True
            data["output"] = output_path

            return send_file(output_path, mimetype='image/tiff', as_attachment=True)
        else: # file is not a tif
            abort(400, "request file is not a geotiff")
    else: # undefined request
        abort(400, "invalid request type") 
    return jsonify(data)

@app.route('/stop_server', methods=['GET'])
def stopServer():
    remove_upload_files()
    os.kill(os.getpid(), signal.SIGINT)
    return jsonify({ "success": True, "message": "Server has been shut down" })
    

if __name__ == "__main__":
    print(("* Loading Keras model, checking upload folder, and starting server..."
        "please wait until server has fully started"))
    #load model
    load_model()
    #check upload folder exits
    check_dir(UPLOAD_FOLDER)
    print(("* Server is ready"))
    #app.run()
    serve(app, host="0.0.0.0", port=5000)