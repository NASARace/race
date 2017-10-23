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

#include <string.h>
#include "race.h"

/*
  simple positional track model

  struct {
    char   *id;
    long   time_msec;
    double lat_deg;
    double lon_deg;
    double alt_m;
    double heading_deg;
    double speed_m_sec;
  }
*/

int race_write_simple_track (databuf_t* db, int pos,
                        char* id, long time_msec, double lat_deg, double lon_deg, double alt_m, 
                        double heading_deg, double speed_m_sec) {
    int id_len = strlen(id);
    int track_len = id_len + 2 + 48;
    if (pos + track_len > db->capacity) return 0; // not enough space left

    int p = pos; 
    p = race_write_string(db, p, id, id_len);
    p = race_write_long(db, p, time_msec);
    p = race_write_double(db, p, lat_deg);
    p = race_write_double(db, p, lon_deg);
    p = race_write_double(db, p, alt_m);
    p = race_write_double(db, p, heading_deg);
    p = race_write_double(db, p, speed_m_sec);
    return p;
}

int race_read_simple_track (databuf_t* db, int pos,
                       char id[], int max_len, 
                       long* time_msec, double* lat_deg, double* lon_deg, double* alt_m, 
                       double* heading_deg, double* speed_m_sec) {
    int p = pos;
    p = race_read_strncpy(db, p,id,max_len);
    p = race_read_long(db, p, time_msec);
    p = race_read_double(db, p, lat_deg);
    p = race_read_double(db, p, lon_deg);
    p = race_read_double(db, p, alt_m);
    p = race_read_double(db, p, heading_deg);
    p = race_read_double(db, p, speed_m_sec);
    return p;
}
