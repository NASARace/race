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
#include <stdio.h>
#include <stdarg.h>
#include <signal.h>
#include <math.h>
#include <inttypes.h>

#include "../race.h"

//--- very simple simulation

#include "testtrack.h"

track_t track = {
    .id = "A",
    .msg_ord = 0,
    .flags = 0,
    .time_millis = 0,
    .heading_deg = 90.0,
    .speed_m_sec = 154.33,  // (~300 kn)
    .vr_m_sec = 0,
    .alt_m = 1600.0,
    .lat_deg = 37.424,
    .lon_deg = -122.098
};

//--- the server callbacks

void info (const char*fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    printf("[INFO]: ");
    vprintf(fmt,ap);
}

void warning (const char*fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    fprintf(stderr,"[WARN]: ");
    vfprintf(stderr,fmt,ap);
}

void error (const char*fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    fprintf(stderr,"[ERROR]: ");
    vfprintf(stderr,fmt,ap);
}

int check_request (char* host, char* service, int cli_flags, char* schema, 
                   epoch_millis_t* sim_millis, int* track_interval){
    printf("client request from %s:%s\n", host,service);
    printf("    flags:    %x\n", cli_flags);
    printf("    schema:   %s\n", schema);
    printf("    sim time: %ld\n", *sim_millis);
    printf("    interval: %d\n", *track_interval);

    int ret = 0;

    if (schema && strcmp(schema, SIMPLE_TRACK_PROTOCOL) != 0){
        printf("unknown schema: %s\n", schema);
        ret |= UNKNOWN_DATA;
    }
    
    printf( (ret == 0) ? "accepted.\n" : "rejected.\n");
    return ret;
}

int write_data(databuf_t* db, int pos) {
    update_position(&track);

    pos = race_write_short(db,pos,TRACK_MSG);
    pos = race_write_short(db,pos,1);  // we only send one track (for now)
    pos = race_write_track_data(db, pos,
                             track.id, track.msg_ord, track.flags,
                             track.time_millis, track.lat_deg, track.lon_deg,
                             track.alt_m, track.heading_deg, track.speed_m_sec, track.vr_m_sec);
    return pos;
}



int read_track_data (databuf_t* db, int pos) {
    char id[MAX_ID_LEN];
    int msg_ord;
    int flags;
    epoch_millis_t time_millis;
    double lat_deg, lon_deg, alt_m, heading_deg, speed_m_sec, vr_m_sec;
    short n_tracks = 0;

    pos = race_read_short(db,pos,&n_tracks); // the number of tracks we received in this message
    printf("received %d tracks from client:\n", n_tracks);    

    for (int i=0; i<n_tracks; i++) {
        pos = race_read_track_data(db, pos, 
                                id, sizeof(id), &msg_ord, &flags, &time_millis, &lat_deg, &lon_deg,
                                &alt_m, &heading_deg, &speed_m_sec, &vr_m_sec);
        if (pos <= 0){
            fprintf(stderr, "error reading track: %d\n", i);
            return 0;                        
        } else {
            printf("   %d: %s, ord=%d, flags=0x%X, t=%"PRId64", lat=%f°, lon=%f°, alt=%f m, hdg=%f°, spd=%f m/sec vr=%f m/sec\n",
                i, id, msg_ord, flags, time_millis, lat_deg, lon_deg, alt_m, heading_deg, speed_m_sec, vr_m_sec);
        }
    }
    return pos;
}

int read_proximity_data (databuf_t* db, int pos) {
    short n_proximities = 0;

    // the (estimated) reference data (at the time of the proximity detection)
    char ref_id[MAX_ID_LEN];
    double ref_lat_deg, ref_lon_deg, ref_alt_m;

    // type and distance of proximity
    double dist_m;
    int flags;

    // the proximity track itself
    char prox_id[MAX_ID_LEN];
    epoch_millis_t time_millis;
    double lat_deg, lon_deg, alt_m, heading_deg, speed_m_sec, vr_m_sec;

    pos = race_read_short(db,pos,&n_proximities); // the number of proximities we received in this message
    printf("received %d proximities from client:\n", n_proximities);
    
    for (int i=0; i<n_proximities; i++){
        pos = race_read_proximity_data(db,pos,
                                       ref_id, MAX_ID_LEN, &ref_lat_deg, &ref_lon_deg, &ref_alt_m,
                                       &dist_m, &flags,
                                       prox_id, MAX_ID_LEN, &time_millis, &lat_deg, &lon_deg, &alt_m,
                                       &heading_deg, &speed_m_sec,&vr_m_sec);
        if (pos <= 0){
            fprintf(stderr, "error reading proximity: %d\n", i);
            return 0;                        
        } else {
            printf("  %2d: ref  = %s, dist=%.0f m, flags=%d\n", i, ref_id, dist_m, flags);
            printf("      prox = %s, t=%"PRId64", lat=%.5f°, lon=%.5f°, alt=%.0f m, hdg=%.0f°, spd=%.1f m/sec, vr=%.1f m/sec\n",
                prox_id, time_millis, lat_deg, lon_deg, alt_m, heading_deg, speed_m_sec, vr_m_sec);
        }
    }
    return pos;
}

int read_data (databuf_t* db, int pos) {
    short msg_type = 0;
    pos = race_read_short(db,pos, &msg_type);

    switch (msg_type) {
        case TRACK_MSG:
          return read_track_data(db,pos);
        case PROXIMITY_MSG:
          return read_proximity_data(db,pos);
        default: 
          printf("received unknown data message of type: %d\n", msg_type);
          return 0;
    }
}

//--- the test driver

local_context_t context = {
    .host = DEFAULT_HOST,
    .port = DEFAULT_SERVER_PORT,
    .interval_millis = 5000,
    .flags = DATA_SENDER | DATA_RECEIVER,

    .check_request = check_request,

    .write_data = write_data,
    .read_data = read_data,

    .info = info,
    .warning = warning,
    .error = error
};

void sig_handler (int sig){
   switch (sig) {
       case SIGINT:       // ctrl-c termination by user
           context.stop_local = true;
           break;
   }
}

int main (int argc, char* argv[]) {
    // example of how to set interrupt handler (without restarting sys calls)
    struct sigaction sa;
    sa.sa_handler = sig_handler;
    sa.sa_flags = 0; // don't restart
    sigaction(SIGINT, &sa, NULL);

    printf("running test server, terminate with ctrl-c\n");
    return !race_server(&context);
}