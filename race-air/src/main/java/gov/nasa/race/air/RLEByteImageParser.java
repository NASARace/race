/*
 * Copyright (c) 2016, United States Government, as represented by the
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

package gov.nasa.race.air;

import gov.nasa.race.util.XmlPullParserIfc;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * a XmlPullParser mixin that reads images from (val,count) run-length encoded text such as ITWS precip messages:
 * {{{
 *   ..<prcp_grid_compressed>15,608 0,24 15,337 0,38 ... </prcp_grid_compressed>..
 * }}}
 *
 * Note - BufferedImage origin is the upper left corner, whereas the message format assumes the
 * origin to be in the *lower* left corner, i.e. we have to flip vertically while we parse
 *
 * Note - while there might be more data in the text, we only parse a (message) given number of scanlines
 *
 * Note - we assume a single image, no z-planes
 */
public interface RLEByteImageParser extends XmlPullParserIfc {

  default void readImage (byte[] scanLine, BufferedImage img) {
    WritableRaster raster = img.getRaster();
    int width = img.getWidth();
    int height = img.getHeight();

    int n = width;    // remaining pixels to fill in scanline
    int i = 0;        // index within scanline
    int j = height-1; // image row (counting backwards since BufferedImages have origin in upper left)

    if (skipToText()) {
      while (parseNextInt()) {
        byte value = (byte) textInt();
        int count = readNextInt();

        while (count > 0) {
          if (count < n) {
            n -= count;
            for (; count > 0; count--) scanLine[i++] = value;

          } else {
            if (n > 0) {
              count -= n;
              for (; n > 0; n--) scanLine[i++] = value;
            }
            raster.setDataElements(0, j, width, 1, scanLine);
            if (j == 0) return; // done, parsed 'height' number of scanlines

            n = width;
            i = 0;
            j -= 1;
          }
        }
      }
    }
  }
}
