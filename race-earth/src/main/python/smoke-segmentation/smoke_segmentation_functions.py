# /*
#  * Copyright (c) 2022, United States Government, as represented by the
#  * Administrator of the National Aeronautics and Space Administration.
#  * All rights reserved.
#  *
#  * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
#  * under the Apache License, Version 2.0 (the "License"); you may not use
#  * this file except in compliance with the License. You may obtain a copy
#  * of the License at http://www.apache.org/licenses/LICENSE-2.0.
#  *
#  * Unless required by applicable law or agreed to in writing, software
#  * distributed under the License is distributed on an "AS IS" BASIS,
#  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  * See the License for the specific language governing permissions and
#  * limitations under the License.
#  */

from osgeo import gdal #must be imported first
import gdal2tiles
from tensorflow import keras
import numpy as np
import matplotlib.pyplot as plt
import cv2
from PIL import Image
import tensorflow as tf
import random
import math
import os
from tensorflow.python.ops.numpy_ops import np_config
import time
import pandas as pd

np_config.enable_numpy_behavior()
gdal.SetConfigOption("GTIFF_SRS_SOURCE", "GEOKEYS")

def parse_log(log_file):
    data = {"date":[],
        "time":[],
        "level":[],
        "module":[],
        "function":[],
        "message":[],
        "timing type":[],
        "timing":[]}
    for line in open(log_file):
        data["date"].append(line.split(" ")[0])
        data["time"].append(line.split(" ")[1] + " "+ line.split(" ")[2])
        data["level"].append(line.split(" ")[3])
        data["module"].append(line.split(" ")[4])
        data["function"].append(line.split(" ")[5])
        msg = " ".join(line.split(" ")[5:]).strip("\n")
        data["message"].append(msg)
        if ": " in msg:
            time_type = msg.split(": ")[0]
            time = msg.split(": ")[-1]
        else:
            time_type = None
            time = None
        data["timing type"].append(time_type)
        data["timing"].append(time)
    
    return pd.DataFrame(data)
    
def convert_mask_to_colors(mask):
    mapping = {0: 0, 1: 127.5, 2: 225}
    k = np.array(list(mapping.keys()))
    v = np.array(list(mapping.values()))
    mapping_ar = np.zeros(k.max()+1,dtype=v.dtype)
    mapping_ar[k] = v
    mask = mapping_ar[mask.astype(int)]
    return mask

def create_mask(pred_mask):
    pred_mask = tf.argmax(pred_mask, axis=-1) 
    pred_mask = tf.expand_dims(pred_mask, axis=-1)
    return pred_mask
    
def plot_output(input, output, create_mask_=True):
    plt.rcParams["figure.figsize"] = (20, 5)
    for i, p in zip(input, output):
        if create_mask_:
            p = create_mask(p)

        plt.subplot(1, 2, 1)
        plt.imshow(i)
        plt.title("Input Image")
        plt.axis("off")
    
        plt.subplot(1, 2, 2)
        plt.imshow(p, cmap="gray")
        plt.title("Predicted Mask (Thresholded)")
        plt.axis("off")

        plt.show()

def get_tif_dataset(tif):
    return gdal.Open(tif)

def convert_tif_to_image(dataset):
    band1 = dataset.GetRasterBand(1) # Red channel
    band2 = dataset.GetRasterBand(2) # Green channel
    band3 = dataset.GetRasterBand(3) # Blue channel
    b1 = band1.ReadAsArray()
    b2 = band2.ReadAsArray()
    b3 = band3.ReadAsArray()
    img = np.dstack((b1, b2, b3))
    return img

def convert_tif_to_mask(dataset):
    band1 = dataset.GetRasterBand(1) # Red channel
    b1 = band1.ReadAsArray()
    return b1

@tf.function
def process_data(originals, target_shape=(512, 512)):
    #print(type(originals[0]))
    #inputs = tf.image.resize(originals, target_shape)
    inputs = [ tf.image.resize(originals[ind], target_shape) for ind in range(len(originals))]
    return inputs

class DataLoader:
    def __init__(self, originals, target_shape=(512, 512)):
        data_loader_start = time.time()
        self.originals = tf.Variable(originals)
        self.AUTOTUNE = tf.data.experimental.AUTOTUNE
        self.target_shape = tf.Variable(target_shape)
        print(time.time()-data_loader_start)
        
    @tf.function(reduce_retracing=True)
    def data_processor(self):
        inputs = [ tf.image.resize(self.originals[ind], self.target_shape) for ind in range(len(self.originals))]
        return inputs

def initiate_model(model_path):
    model = keras.models.load_model(filepath=model_path, compile=False)
    model.compile(loss="sparse_categorical_crossentropy", 
                  optimizer=tf.keras.optimizers.Adam(learning_rate=0.00001), 
                  metrics=["accuracy", 
                           tf.keras.metrics.MeanIoU(num_classes=3, sparse_y_true=True, sparse_y_pred=False)]
            )
    return model

def get_segmented_tif(original_tif, mask, output_path):
    driver = gdal.GetDriverByName("GTiff")
    [rows, cols] = mask.shape
    output_tif= driver.Create(output_path, cols, rows, 1, gdal.GDT_Byte)
    output_tif.SetGeoTransform(original_tif.GetGeoTransform())##sets same geotransform as input
    output_tif.SetProjection(original_tif.GetProjection())##sets same projection as input
    mask = convert_mask_to_colors(mask) #converts mask to mapping colors
    new_metadata = {'0': 'no smoke no clouds', '127.5': 'smoke', '255': 'cloud'}
    new_metadata.update(original_tif.GetMetadata())
    output_tif.SetMetadata(new_metadata)##sets same metadata as input and adds key for interpreting segmetation mask
    output_tif.GetRasterBand(1).WriteArray(mask)
    output_tif.GetRasterBand(1).SetNoDataValue(0)
    output_tif.FlushCache() ##saves to disk
    return output_tif

def get_segmeneted_mutiband_tif(original_tif_geo_transform, original_tif_proj, original_tif_meta, mask, output_path):
    driver = gdal.GetDriverByName("GTiff")
    mask = mask.reshape(mask.shape[:2])
    [rows, cols] = mask.shape
    output_tif= driver.Create(output_path, cols, rows, 3, gdal.GDT_Byte)
    output_tif.SetGeoTransform(original_tif_geo_transform)##sets same geotransform as input
    output_tif.SetProjection(original_tif_proj)##sets same projection as input
    new_metadata = {'0': 'no smoke no clouds', '1': 'smoke', '2': 'cloud'}
    new_metadata.update(original_tif_meta)
    output_tif.SetMetadata(new_metadata)##sets same metadata as input and adds key for interpreting segmetation mask
    smoke_mask = np.zeros(mask.shape)
    smoke_mask[np.where(mask==1)] = 1
    cloud_mask = np.zeros(mask.shape)
    cloud_mask[np.where(mask==2)] = 2
    output_tif.GetRasterBand(2).WriteArray(smoke_mask)
    output_tif.GetRasterBand(2).SetNoDataValue(0)
    output_tif.GetRasterBand(3).WriteArray(cloud_mask)
    output_tif.GetRasterBand(3).SetNoDataValue(0)
    output_tif.FlushCache() ##saves to disk
    return output_tif

def get_probability_tif(original_tif, probabilities, output_path):
    driver = gdal.GetDriverByName("GTiff")
    [rows, cols, layers] = probabilities.shape
    output_tif= driver.Create(output_path, cols, rows, layers, gdal.GDT_Byte)
    output_tif.SetGeoTransform(original_tif.GetGeoTransform())##sets same geotransform as input
    output_tif.SetProjection(original_tif.GetProjection())##sets same projection as input
    new_metadata = {"1": "no smoke no clouds", "2": "smoke", "3": "cloud"}
    #new_metadata.update(original_tif.GetMetadata())
    vfunc = np.vectorize(convert_prob_to_interval)
    output_tif.SetMetadata(new_metadata)##sets same metadata as input and adds key for interpreting segmetation mask
    output_tif.GetRasterBand(1).WriteArray(vfunc(probabilities[:, :, 0]))
    output_tif.GetRasterBand(2).WriteArray(vfunc(probabilities[:, :, 1]))
    output_tif.GetRasterBand(3).WriteArray(vfunc(probabilities[:, :, 2]))
    output_tif.FlushCache() ##saves to disk
    return output_tif

def convert_prob_to_interval(prob):
    p = round(prob, 3)
    if p < 0.25:
        i = 1
    elif 0.25 <= p and p < 0.5:
        i = 2
    elif 0.5 <= p and p < 0.75:
        i = 3
    elif 0.75 <= p and p < 1:
        i = 4
    elif p == 1:
        i = 5
    return i

def smoke_segmentation_raw(tif_dataset, segmentation_model, target_shape, output_path, tile=True, overlap=0):
    #convert to image
    image = convert_tif_to_image(tif_dataset)
    #tile image
    if tile:
        images, full_tiled_shape, padding_tuple, overlap = get_tiles(image, target_shape, overlap=overlap)
        original_shape = target_shape
    else:
        images = np.array([image])
        original_shape = images.shape[1:3]
    #format images
    input_dataset = DataLoader(images, target_shape)
    #print(type(images[0]))
    inputs = process_data(input_dataset.originals, input_dataset.target_shape)#
    #inputs = input_dataset.data_processor()
    #inputs = tf. convert_to_tensor(images)
    inputs_tf = tf.data.Dataset.from_tensor_slices((inputs))
    inputs_tf = inputs_tf.batch(32)
    #run segmentation 
    pred = segmentation_model.predict(inputs_tf)
    if tile:
        pred_reshaped = reconstruct_image(pred, full_tiled_shape, padding_tuple, target_shape, overlap=overlap)
    else:
        pred_reshaped = pred[0]
    pred_mask = create_mask(pred_reshaped)
    #plot_output([image], [pred_mask], create_mask_=False)
    #output_tif = get_probability_tif(tif_dataset, pred_reshaped, output_path)
    output_tif = get_segmeneted_mutiband_tif(tif_dataset, pred_mask, output_path)
    return output_tif

def smoke_segmentation(tif_dataset, segmentation_model, target_shape, output_path, tile=True, plot=False, separate_layers=False, overlap=0):
    #convert to image
    image = convert_tif_to_image(tif_dataset)
    #tile image
    if tile:
        images, full_tiled_shape, padding_tuple, overlap = get_tiles(image, target_shape, overlap)
        original_shape = target_shape
    else:
        images = np.array([image])
        original_shape = images.shape[1:3]
    #format images
    input_dataset = DataLoader(images, target_shape)
    inputs = input_dataset.data_processor()
    inputs_tf = tf.data.Dataset.from_tensor_slices((inputs))
    inputs_tf = inputs_tf.batch(32)
    #run segmentation 
    pred = segmentation_model.predict(inputs_tf)
    pred_mask = create_mask(pred)
    #reshape outputs to match originial shape
    original_shape = (original_shape[1], original_shape[0]) # for some reason these are always transposed?
    masks_reshaped = [cv2.resize(np.float32(pred_mask[i]), original_shape, interpolation=0) for i in range(len(pred_mask))]
    if tile:
        mask_reshaped = reconstruct_image(masks_reshaped, full_tiled_shape, padding_tuple[:2], target_shape, overlap)
    else:
        mask_reshaped = masks_reshaped[0]
    #plot
    if plot:
        plot_output([image], [mask_reshaped], create_mask_=False)
    #convert binary rastor to geotiff with preserved info 
    if separate_layers == True:
        smoke_mask = mask_reshaped.copy() #replace cloud vals to 0
        smoke_mask[smoke_mask==2] = 0
        smoke_output_path = output_path.replace(".tif", "_smoke.tif")
        smoke_output_tif = get_segmented_tif(tif_dataset, smoke_mask, smoke_output_path)
        cloud_mask = mask_reshaped.copy() #replace smoke vals to 0
        cloud_mask[cloud_mask==1] = 0
        cloud_output_path = output_path.replace(".tif", "_cloud.tif")
        cloud_output_tif = get_segmented_tif(tif_dataset, cloud_mask, cloud_output_path)
        output_tif = [smoke_output_tif, cloud_output_tif]
        output_paths = [smoke_output_path, cloud_output_path]
    else:
        output_tif = get_segmented_tif(tif_dataset, mask_reshaped, output_path)
        output_paths = [output_path]
    return output_tif, output_paths

def unpad(x, pad_width):
    slices = []
    for c in pad_width:
        e = None if c[1] == 0 else -c[1]
        slices.append(slice(c[0], e))
    return x[tuple(slices)]

def reconstruct_image(tiled_array, tiled_shape, padding_tuple, target, overlap):
    rows = []
    if type(target) != int:
        target = target[0]
    if tiled_shape == (1,1):
        full_image = tiled_array[0]
    else:
        for i in range(tiled_shape[0]):
            row = []
            for j in range(tiled_shape[1]):
                if i == 0:
                    vert_start = 0
                    vert_stop = target-overlap[0]
                elif i < tiled_shape[0]-1:
                    vert_start = overlap[0]
                    vert_stop = target-overlap[0]
                else:
                    vert_start = overlap[0]
                    vert_stop = target
                if j == 0:
                    horz_start = 0
                    horz_stop = target-overlap[1]
                elif j < tiled_shape[1]-1:
                    horz_start = overlap[1]
                    horz_stop = target-overlap[1]
                else:
                    horz_start = overlap[1]
                    horz_stop = target
                full = tiled_array[tiled_shape[1]*i+j][vert_start:vert_stop, horz_start:horz_stop]
                row.append(full)   
            rows.append(np.concatenate(row, axis=1))
        full_image = np.concatenate(rows, axis=0)
    full_image = unpad(full_image, padding_tuple)
    return full_image

def split(img, target_shape, overlap):
    split_start = time.time()
    sh = list(img.shape)
    sh[0], sh[1] = sh[0] + (overlap[0] * 2), sh[1] + (overlap[1] * 2)
    splitted = []
    v_stride = target_shape[0]
    h_stride = target_shape[1]
    v_step = target_shape[0] + 2 * overlap[0]
    h_step = target_shape[1] + 2 * overlap[1]
    nrows, ncols = max((img.shape[0] - overlap[0]*2) // target_shape[0], 1), max((img.shape[1] - overlap[1]*2) // target_shape[1], 1)
    for i in range(nrows):
        for j in range(ncols):
            h_start = j*h_stride
            v_start = i*v_stride
            cropped = img[v_start:v_start+v_step, h_start:h_start+h_step]
            splitted.append(cropped)
    return splitted, (nrows, ncols)

def get_tiles(image, target_shape, overlap=0):
    padding_tuple = ((0,0), (0,0), (0,0)) 
    padded_image = image
    overlap = [overlap, overlap]
    if target_shape == image.shape[:2]:
        images = [image]
        full_tiled_shape = (1,1)
    else:
        target_shape = list(target_shape)
        if target_shape[0] < image.shape[0]:
            target_shape[0] = target_shape[0]-(2*overlap[0])
        elif target_shape[0] >= image.shape[0]:
            overlap[0] = 0
        if target_shape[1] < image.shape[1]:
            target_shape[1] = target_shape[1]-(2*overlap[1])
        elif target_shape[1] >= image.shape[1]:
            overlap[1] = 0
        target_shape = tuple(target_shape)
        total_width = (target_shape[0]*np.ceil(image.shape[0]/(target_shape[0]))) + (overlap[0]*2) # *2
        total_height = (target_shape[1]*np.ceil(image.shape[1]/(target_shape[1]))) + (overlap[1]*2) # *2
        remainders = (total_width - image.shape[0], total_height - image.shape[1])
        padding_tuple = ((0, int(remainders[0])), (0, int(remainders[1])), (0,0))
        padded_image = np.pad(image, padding_tuple, 'constant', constant_values=(0))
        tiled_array, full_tiled_shape = split(padded_image, target_shape, overlap)
        images = tiled_array
    return images, full_tiled_shape, padding_tuple, overlap

def smoke_segmentation_png_jpg(input, segmentation_model, target_shape, output_path, tile=True, plot=False, overlap=0): 
    image = Image.open(input).convert('RGB')
    image = np.asarray(image)
    #tile image
    if tile:
        images, full_tiled_shape, padding_tuple, overlap = get_tiles(image, target_shape, overlap)
        original_shape = target_shape
    else:
        images = np.array([image])
        original_shape = images.shape[1:3]
    #format images
    input_dataset = DataLoader(images, target_shape)
    inputs = input_dataset.data_processor()
    inputs_tf = tf.data.Dataset.from_tensor_slices((inputs)).batch(len(images))
    #run segmentation 
    pred = segmentation_model.predict(inputs_tf)
    pred_mask = create_mask(pred)
    #reshape outputs to match originial shape
    original_shape = (original_shape[1], original_shape[0]) # for some reason these are always transposed?
    masks_reshaped = [cv2.resize(np.float32(pred_mask[i]), original_shape, interpolation=0) for i in range(len(pred_mask))]
    if tile:
        mask_reshaped = reconstruct_image(masks_reshaped, full_tiled_shape, padding_tuple[:2], target_shape, overlap)
    else:
        mask_reshaped = masks_reshaped[0]
    #plot
    if plot:
        plot_output([image], [mask_reshaped], create_mask_=False)
    output = convert_mask_to_colors(mask_reshaped)
    im = Image.fromarray(output).convert('RGB')
    im.save(output_path)
    return output

def check_dir(output_folder):
    if os.path.isdir(output_folder) == False:
        os.makedirs(output_folder)

def create_wms_tiles(tif_path, output_folder, tile_options={}):
    default_options = {
    'tile_size': 256,  
    'srs':'EPSG:4326'} 
    default_options.update(tile_options)
    check_dir(output_folder)
    gdal2tiles.generate_tiles(tif_path, output_folder, **default_options)