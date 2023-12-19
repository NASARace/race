import zipfile
import tensorflow as tf
from flask import Flask, request, jsonify, abort, redirect, send_file, Response
from werkzeug.utils import secure_filename
from waitress import serve
from smoke_segmentation_functions import initiate_model, get_tif_dataset, check_dir, convert_tif_to_image,  get_tiles, reconstruct_image, create_mask, get_segmeneted_mutiband_tif
import os, sys
import atexit
import shutil
import signal
import uuid
import logging
import time
import numpy as np
import psutil
from keras import backend as K

os.environ["USE_PATH_FOR_GDAL_PYTHON"]="NO"

#logging set up
logger = logging.getLogger('waitress')

console = logging.StreamHandler()
console.setLevel(logging.ERROR)

file = logging.FileHandler('smoke-segmentation.log')
file.setLevel(logging.DEBUG)

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

def close_files():
    # close keras io
    sys.stdout.close()
    K.clear_session()
    # close logs
    logger.removeHandler(console)
    logger.removeHandler(file)
    console.close()
    file.close()
    return

def remove_upload_files():
    shutil.rmtree(UPLOAD_FOLDER)

def load_model():
    model_path = os.path.join(os.getcwd(), 'models/final_BT_unet.h5')
    #model_path = os.path.join(os.getcwd(), "models", 'BT_unet_T')
    global segmentation_model
    #print(model_path)
    segmentation_model = initiate_model(model_path)

# route definition

@app.route("/predict", methods=["POST", "GET"])
def predict():
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
        logger.info("file import time: %f", file_write-start)
            
        
        if check_file_type(file_path):
            try:
                # open tif
                tif_dataset = get_tif_dataset(file_path)
                tif_open = time.time()
                logger.info("tif opening time: %f", tif_open-file_write)
                # get original tif info
                original_tif_geo_transform = tif_dataset.GetGeoTransform()
                original_tif_proj = tif_dataset.GetProjection()
                original_tif_meta = tif_dataset.GetMetadata()
                tif_info = time.time()
                logger.info("tif info copy time: %f", tif_info-tif_open)
                # convert to image
                image = convert_tif_to_image(tif_dataset)
                del tif_dataset #closes original tiff
                tif_to_image = time.time()
                logger.info("tif to image time: %f", tif_to_image-tif_info)
                # process tif into tiles
                images, full_tiled_shape, padding_tuple, overlap = get_tiles(image, target_shape, overlap=48)
                tif_tile = time.time()
                logger.info("image tiling time: %f", tif_tile-tif_to_image)
                # format images
                inputs = tf.convert_to_tensor(np.asarray(images))#tf.image.resize(images, target_shape)
                inputs_tf = tf.data.Dataset.from_tensor_slices((inputs))
                inputs_tf_batch = inputs_tf.batch(32)
                format_time = time.time()
                logger.info("image formatting time: %f", format_time-tif_tile)
                # run segmentation
                pred = segmentation_model.predict( inputs_tf_batch)
                pred_time = time.time()
                logger.info("segmentation time: %f", pred_time-format_time)
                # reconstruction
                pred_reshaped = reconstruct_image(pred, full_tiled_shape, padding_tuple, target_shape, overlap=overlap)
                reconstruction_time = time.time()
                logger.info("reconstruction time: %f", reconstruction_time-pred_time)
                # mask and final output
                pred_mask = create_mask(pred_reshaped)
                output_path =  file_path.replace(".tif", "_segmented.tif")
                output = get_segmeneted_mutiband_tif(original_tif_geo_transform, original_tif_proj, original_tif_meta, pred_mask, output_path)
                output_time = time.time()
                logger.info("output time: %f", output_time-reconstruction_time)
                # clear memory
                del output #closes new tiff
                # response
                K.clear_session()
                return send_file(output_path, mimetype='image/tiff', as_attachment=True)
            except:
                logger.error("error during segmentation")
                abort(400, "error during segmentation")
        else: # file is not a tif
            logger.error("file not a geotiff")
            abort(400, "request file is not a geotiff")
    else: # undefined request
        logger.error("invalid request")
        abort(400, "invalid request type") 

@app.route('/stop_server', methods=['GET'])
def stop_server():
    close_files()
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
    serve(app, host="0.0.0.0", port=5000)