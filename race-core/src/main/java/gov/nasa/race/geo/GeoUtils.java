/*
 * Copyright (c) 2017, United States Government, as represented by the
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
package gov.nasa.race.geo;

import static java.lang.Math.*;

/**
 * a collection of constants and utility functions
 *
 * we keep this in Java since array initialization and access is slightly more efficient in Java
 * note - this class is self-contained and static so that the compiler can optimize
 */
public class GeoUtils {

    static final double RE = 6378137.0; // earth semi-major in [m]
    static final double C = Math.PI / 9000;

    static final float[] cos100 = generateCos100Table();

    static double degToRad (double deg){
        return Math.PI * deg / 180.0;
    }

    /**
     * generate a float table for cos() in 0.01 deg steps
     */
    static float[] generateCos100Table() {
        float[] v = new float[9001];
        for (int i = 0; i<=9000; i++){
            double deg = ((double)i)/100;
            double rad = degToRad(deg);
            v[i] = (float)cos(rad);
        }
        return v;
    }

    /**
     * the gold standard for greatcircle  distance approximation, which makes heavy use of trig functions
     * @param lat1,lon1,lat2,lon2 latitude and longitude in radians
     * @param alt altitude in meters
     * @return approximate distance in meters
     */
    public static double haversineDistanceRad (double lat1, double lon1, double lat2, double lon2, double alt) {
        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;

        double sinDlat = sin(dLat/2);
        double sinDlon = sin(dLon/2);

        double a = sinDlat*sinDlat + cos(lat1) * cos(lat2) * sinDlon*sinDlon;
        double c = 2 * atan2(sqrt(a), sqrt(1 - a));

        return (RE + alt) * c;
    }

    /**
     * euclidean approximation for small distances ( <1m diff for dist <10nm)
     * About 5x faster than haversine
     *
     * @param lat1,lon1,lat2,lon2 latitude and longitude in radians
     * @param alt altitude in meters
     * @return approximate distance in meters
     */
    public static double euclideanDistanceRad (double lat1, double lon1, double lat2, double lon2, double alt) {
        double x = lat2 - lat1;
        double y = (lon2 - lon1) * cos100[(int)(round((lat1+lat2)/C))];
        return sqrt(x*x + y*y) * (RE + alt);
    }
}
