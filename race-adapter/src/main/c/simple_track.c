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

/*
 * functions to read/write gov.nasa.race.track.SimpleTrackProtocol data messages
 * 
 * TODO - this should handle protocol versions
 */

#include <string.h>
#include "race.h"

    /**
    protocol SimpleTrackProtocol {
        record SimpleTrack {
            string id;
            int msg_ordinal; // consecutive, starting with 1 
            int flags;

            timestamp_ms time_millis;
            double lat_deg;
            double lon_deg;
            double alt_m;
            double heading_deg;
            double speed_m_sec;
            double vr_m_sec;
        }
        record TrackMsg {
            int msg_type = 1;
            short n_records;
            array<SimpleTrack> tracks;
        }


        record ProximityChange {
            string ref_id; // of track this is a proximity for
            double lat_deg;
            double lon_deg;
            double alt_m;
            double dist_m;
            int    flags;

            string id; // of proximity
            // there is no msg ordinal, this might be a extrapolated position
            timestamp_ms time_millis;
            double lat_deg;
            double lon_deg;
            double alt_m;
            double heading_deg;
            double speed_m_sec;
        }
        record ProximityMsg {
            int msg_type = 2;
            short n_records;
            array<ProximityChange> proximities;
        }


        record DroppedTrack {
            string id;
            int flags;
            timestamp_ms time_millis;
        }
        record DropMsg {
            int msg_type = 3;
            short n_records;
            array<DroppedTrack> drops;
        }
    }
    **/

int race_write_track_data (databuf_t* db, int pos,
                        char* id, int msg_ordinal, int flags, 
                        epoch_millis_t time_millis, double lat_deg, double lon_deg, double alt_m, 
                        double heading_deg, double speed_m_sec, double vr_m_sec) {
    int id_len = strlen(id);
    int track_len = id_len + 2 + 64;
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
    return p;
}

int race_read_track_data (databuf_t* db, int pos,
                       char id[], int max_len, int* msg_ordinal, int* flags,
                       epoch_millis_t* time_millis, double* lat_deg, double* lon_deg, double* alt_m, 
                       double* heading_deg, double* speed_m_sec, double* vr_m_sec) {
    int p = pos;
    p = race_read_strncpy(db, p,id,max_len);
    p = race_read_int(db,p, msg_ordinal);
    p = race_read_int(db,p, flags);
    p = race_read_long(db, p, time_millis);
    p = race_read_double(db, p, lat_deg);
    p = race_read_double(db, p, lon_deg);
    p = race_read_double(db, p, alt_m);
    p = race_read_double(db, p, heading_deg);
    p = race_read_double(db, p, speed_m_sec);
    p = race_read_double(db, p, vr_m_sec);
    return p;
}

int race_write_proximity_data(databuf_t *db, int pos, 
                             char *ref_id, double ref_lat_deg, double ref_lon_deg, double ref_alt_m,
                             double dist_m, int flags, 
                             char *prox_id, epoch_millis_t time_millis, double lat_deg, double lon_deg, double alt_m, 
                             double heading_deg, double speed_m_sec, double vr_m_sec) {
    int ref_id_len = strlen(ref_id);
    int prox_len = ref_id_len + 2 + 36;
    if (pos + prox_len > db->capacity) return 0; // not enough space left

    int p = pos; 
    p = race_write_string(db, p, ref_id, ref_id_len);
    p = race_write_double(db, p, ref_lat_deg);
    p = race_write_double(db, p, ref_lon_deg);
    p = race_write_double(db, p, ref_alt_m);
    p = race_write_double(db, p, dist_m);
    p = race_write_int(db, p, flags);

    p = race_write_string(db, p, prox_id, strlen(prox_id));
    p = race_write_long(db, p, time_millis);
    p = race_write_double(db, p, lat_deg);
    p = race_write_double(db, p, lon_deg);
    p = race_write_double(db, p, alt_m);
    p = race_write_double(db, p, heading_deg);
    p = race_write_double(db, p, speed_m_sec);
    p = race_write_double(db, p, vr_m_sec);

    return p;
}

int race_read_proximity_data(databuf_t *db, int pos, 
                             char ref_id[], int max_ref_len,
                             double *ref_lat_deg, double *ref_lon_deg, double *ref_alt_m,
                             double *dist_m, int *flags, 
                             char prox_id[], int max_prox_len,
                             epoch_millis_t *time_millis, double *lat_deg, double *lon_deg, double *alt_m, 
                             double *heading_deg, double *speed_m_sec, double *vr_m_sec) {
    int p = pos;
    p = race_read_strncpy(db, p, ref_id, max_ref_len);
    p = race_read_double(db, p, ref_lat_deg);
    p = race_read_double(db, p, ref_lon_deg);
    p = race_read_double(db, p, ref_alt_m);
    p = race_read_double(db, p, dist_m);
    p = race_read_int(db, p, flags);

    p = race_read_strncpy(db, p, prox_id, max_prox_len);
    p = race_read_long(db, p, time_millis);
    p = race_read_double(db, p, lat_deg);
    p = race_read_double(db, p, lon_deg);
    p = race_read_double(db, p, alt_m);
    p = race_read_double(db, p, heading_deg);
    p = race_read_double(db, p, speed_m_sec);
    p = race_read_double(db, p, vr_m_sec);

    return p;
}

int race_write_drop_data (databuf_t* db, int pos, char* id, int flags, epoch_millis_t time_millis) {
    int id_len = strlen(id);
    int track_len = id_len + 2 + 12;
    if (pos + track_len > db->capacity) return 0; // not enough space left

    int p = pos; 
    p = race_write_string(db, p, id, id_len);
    p = race_write_int(db,p,flags);
    p = race_write_long(db, p, time_millis);
    return p;
}

int race_read_drop_data (databuf_t* db, int pos, char id[], int max_len, int* flags, epoch_millis_t* time_millis) {
    int p = pos;
    p = race_read_strncpy(db, p,id,max_len);
    p = race_read_int(db,p, flags);
    p = race_read_long(db, p, time_millis);
    return p;
}
