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

#include <math.h>
#include "../race.h"
#include "testtrack.h"

void update_position(track_t* track) {
    track->msg_ord++;

    if (track->time_millis == 0) {
        track->time_millis = race_epoch_millis();

    } else {
        epoch_millis_t t_now = race_epoch_millis();
        double dist = ((t_now - track->time_millis) / 1000.0) * track->speed_m_sec;
        double delta = dist / (R + track->alt_m);
        double lat = RAD(track->lat_deg);
        double lon = RAD(track->lon_deg);
        double hdg = RAD(track->heading_deg);

        double lat1 = asin(sin(lat) * cos(delta) + cos(lat) * sin(delta) * cos(hdg));
        double lon1 = lon + atan2(sin(hdg) * sin(delta) * cos(lat), cos(delta) - sin(lat) * sin(lat1));

        track->time_millis = t_now;
        track->lat_deg = DEG(lat1);
        track->lon_deg = DEG(lon1);
    }
}