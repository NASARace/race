#API TEST

import unittest
import sys
import os
sys.path.append(os.path.join(os.path.dirname(os.path.realpath(__file__)),".."))

from smoke_segmentation_functions import *
import requests
from osgeo import gdal #must be imported first
import json
import numpy as np

class test_smoke_segmentation_functions(unittest.TestCase):
    def setUp(self):
        self.test_tiff = os.path.join(os.path.dirname(os.getcwd()), 'data', 'test_data', "GOES16-ABI-CONUS-GEOCOLOR-5000x3000.tif")
        self.test_png = os.path.join(os.path.dirname(os.getcwd()), 'data', 'test_data', "Screenshot 2022-10-24 120046.png")
        self.test_jpg = os.path.join(os.path.dirname(os.getcwd()), 'data', 'test_data', "Screenshot 2022-10-24 120046.jpg")
        self.api = "http://localhost:5000/predict"
        self.output_files = [""]
        self.headers = {'content-type': 'application/json'}
        self.target_shape = (64,64)
        self.test_array_1 = np.random.rand(64,64,3)
        self.test_array_2 = np.random.rand(120,64,3)
        self.test_array_3 = np.random.rand(64,120,3)
        self.test_array_4 = np.random.rand(256,256,3)
        self.test_array_5 = np.random.rand(50,50,3)
        self.test_array_6 = np.random.rand(300, 350, 3)
        self.tile_kernel = None

    def tearDown(self):
        for file in self.output_files:
            if os.path.exists(file):
                os.remove(file)
        
    def test_tiff_segmentation(self):
        payload = {"file": self.test_tiff}
        payload = json.dumps(payload)
        # submit the request
        r = requests.post(self.api, data=payload, headers=self.headers).json()
        self.assertTrue(r["success"])
        self.output_files = r["output"]
        #check metadata
        original_tiff = gdal.Open(self.test_tiff)
        for output_path in r["output"]:
            output_tiff = gdal.Open(output_path)
            self.assertEqual(original_tiff.GetGeoTransform(), output_tiff.GetGeoTransform())
            self.assertEqual(original_tiff.GetProjection(), output_tiff.GetProjection())
            original_metadata = original_tiff.GetMetadata()
            for k in original_metadata:
                self.assertEqual(original_metadata[k], output_tiff.GetMetadata()[k])
            del output_tiff
        del original_tiff
            

    def test_png_segmentation(self):
        payload = {"file": self.test_png}
        payload = json.dumps(payload)
        # submit the request
        r = requests.post(self.api, data=payload, headers=self.headers).json()
        self.assertTrue(r["success"])
        self.output_files = r["output"]

    def test_jpg_segmentation(self):
        payload = {"file": self.test_jpg}
        payload = json.dumps(payload)
        # submit the request
        r = requests.post(self.api, data=payload, headers=self.headers).json()
        self.assertTrue(r["success"])
        self.output_files = r["output"]

    def test_get_tiles(self):
        images, full_tiled_shape, padding_tuple, overlap = get_tiles(self.test_array_1, self.target_shape, overlap=0)
        self.assertEqual((1, 1), full_tiled_shape)
        self.assertEqual(((0,0), (0,0), (0,0)), padding_tuple)
        for i in images:
            self.assertEqual(self.target_shape, i.shape[:2])

        images,  full_tiled_shape, padding_tuple, overlap = get_tiles(self.test_array_2, self.target_shape, overlap=0)
        self.assertEqual((2, 1), full_tiled_shape)
        self.assertEqual(((0,8), (0,0), (0,0)), padding_tuple)
        for i in images:
            self.assertEqual(self.target_shape, i.shape[:2])

        images,  full_tiled_shape, padding_tuple, overlap = get_tiles(self.test_array_3, self.target_shape, overlap=0)
        self.assertEqual((1, 2,), full_tiled_shape)
        self.assertEqual(((0,0), (0,8), (0,0)), padding_tuple)
        for i in images:
            self.assertEqual(self.target_shape, i.shape[:2])

        images,  full_tiled_shape, padding_tuple, overlap = get_tiles(self.test_array_4, self.target_shape, overlap=0)
        self.assertEqual((4, 4), full_tiled_shape)
        self.assertEqual(((0,0), (0,0), (0,0)), padding_tuple)
        for i in images:
            self.assertEqual(self.target_shape, i.shape[:2])

        images,  full_tiled_shape, padding_tuple, overlap = get_tiles(self.test_array_5, self.target_shape, overlap=0)
        self.assertEqual((1, 1), full_tiled_shape)
        self.assertEqual(((0,14), (0,14), (0,0)), padding_tuple)
        for i in images:
            self.assertEqual(self.target_shape, i.shape[:2])

        images,  full_tiled_shape, padding_tuple, overlap = get_tiles(self.test_array_6, self.target_shape, overlap=0)
        self.assertEqual((5, 6), full_tiled_shape)
        self.assertEqual(((0,20), (0,34), (0,0)), padding_tuple)
        for i in images:
            self.assertEqual(self.target_shape, i.shape[:2])
        

    def test_unpad(self):
        images = [self.test_array_1, self.test_array_2, self.test_array_3, self.test_array_4, self.test_array_5, self.test_array_6]
        pad_widths = [((0,0), (0,0), (0,0)), ((0,1), (0,10), (0,1)), ((1,1), (1,10), (0,1)), ((1,0), (10,0), (1,0))]
        for pad_width in pad_widths:
            for image in images:
                unpadded = unpad(image, pad_width)
                self.assertEqual((image.shape[0]-sum(pad_width[0]),(image.shape[1]-sum(pad_width[1])),(image.shape[2]-sum(pad_width[2]))), unpadded.shape)

    def test_get_image_tiles_reshape(self):
        images = [self.test_array_1, self.test_array_4]
        for image in images:
            tiled_array, tiled_shape = split(image, (64, 64), [0,0])
            self.assertEqual(tiled_shape, (image.shape[0]/64, image.shape[0]/64))
    
    def test_tiling_integration(self):
        test_images = [self.test_array_1, self.test_array_2, self.test_array_3, self.test_array_4, self.test_array_5, self.test_array_6]
        for image in test_images:
            images, full_tiled_shape, padding_tuple, overlap = get_tiles(image, self.target_shape, overlap=0)
            full_image = reconstruct_image(images, full_tiled_shape, padding_tuple, target=self.target_shape[0], overlap=overlap)
            for i in images:
                self.assertEqual(self.target_shape, i.shape[:2])
            self.assertEqual(image.shape, full_image.shape)
            self.assertEqual(image.all(), full_image.all())
            images, full_tiled_shape, padding_tuple, overlap = get_tiles(image, self.target_shape, overlap=12)
            full_image = reconstruct_image(images, full_tiled_shape, padding_tuple, target=self.target_shape[0], overlap=overlap)
            for i in images:
                self.assertEqual(self.target_shape, i.shape[:2])
            self.assertEqual(image.shape, full_image.shape)
            self.assertTrue(np.array_equal(image, full_image))
            self.assertEqual(image.all(), full_image.all())
            images, full_tiled_shape, padding_tuple, overlap = get_tiles(image, self.target_shape, overlap=16)
            full_image = reconstruct_image(images, full_tiled_shape, padding_tuple, target=self.target_shape[0], overlap=overlap)
            for i in images:
                self.assertEqual(self.target_shape, i.shape[:2])
            self.assertEqual(image.shape, full_image.shape)
            self.assertTrue(np.array_equal(image, full_image))
            self.assertEqual(image.all(), full_image.all())
            images, full_tiled_shape, padding_tuple, overlap = get_tiles(image, self.target_shape, overlap=20)
            full_image = reconstruct_image(images, full_tiled_shape, padding_tuple, target=self.target_shape[0], overlap=overlap)
            for i in images:
                self.assertEqual(self.target_shape, i.shape[:2])
            self.assertEqual(image.shape, full_image.shape)
            self.assertTrue(np.array_equal(image, full_image))
            self.assertEqual(image.all(), full_image.all())


if __name__ == '__main__':
    unittest.main()
