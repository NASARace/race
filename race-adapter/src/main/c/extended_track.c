/*
 * Copyright (c) 2018, United States Government, as represented by the
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

/*
 * functions for extended track protocol, which sends more vehicle state per track data message
 */

#include "race.h"
#include <string.h>

/**
        record ExtendedTrack {
            <SimpleTrack>
            double pitch_deg;
            double roll_deg;
            string track_type;
        }
        record TrackMsg {
            int msg_type = 1;
            short n_records;
            array<ExtendedTrack> tracks;
        } 
 
 **/

int race_write_xtrack_data (databuf_t* db, int pos,
                        char* id, int msg_ordinal, int flags, 
                        epoch_millis_t time_millis, double lat_deg, double lon_deg, double alt_m, 
                        double heading_deg, double speed_m_sec, double vr_m_sec,
                        double pitch_deg, double roll_deg, char* track_type) {
    int id_len = strlen(id);
    int track_len = id_len + 2 + 80;
    if (pos + track_len > db->capacity) return 0; // not enough space left

    int p = pos; 
    p = race_write_string(db, p, id, id_len);
    p = race_write_int(db, p, msg_ordinal);
    p = race_write_int(db,p,flags);
    p = race_write_long(db, p, time_millis);
    p = race_write_double(db, p, lat_deg);
    p = race_write_double(db, p, lon_deg);
    p = race_write_double(db, p, alt_m);
    p = race_write_double(db, p, heading_deg);
    p = race_write_double(db, p, speed_m_sec);
    p = race_write_double(db, p, vr_m_sec);

    p = race_write_double(db, p, pitch_deg);
    p = race_write_double(db, p, roll_deg);
    p = race_write_string(db, p, track_type, strlen(track_type));
    return p;
}

int race_read_xtrack_data (databuf_t* db, int pos,
                       char id[], int max_id_len, int* msg_ordinal, int* flags,
                       epoch_millis_t* time_millis, double* lat_deg, double* lon_deg, double* alt_m, 
                       double* heading_deg, double* speed_m_sec, double* vr_m_sec,
                       double* pitch_deg, double* roll_deg, char track_type[], int max_type_len) {
    int p = pos;
    p = race_read_strncpy(db, p,id,max_id_len);
    p = race_read_int(db,p, msg_ordinal);
    p = race_read_int(db,p, flags);
    p = race_read_long(db, p, time_millis);
    p = race_read_double(db, p, lat_deg);
    p = race_read_double(db, p, lon_deg);
    p = race_read_double(db, p, alt_m);
    p = race_read_double(db, p, heading_deg);
    p = race_read_double(db, p, speed_m_sec);
    p = race_read_double(db, p, vr_m_sec);

    p = race_read_double(db, p, pitch_deg);
    p = race_read_double(db, p, roll_deg);
    p = race_read_strncpy(db, p, track_type, max_type_len);
    return p;
}