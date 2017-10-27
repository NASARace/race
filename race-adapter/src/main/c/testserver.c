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

#include "race.h"

//--- very simple simulation

#include "testtrack.h"

track_t track = {
    .id = "XYZ333",
    .time_msec = 0,
    .speed_m_sec = 205.7,  // (~400 kn)
    .heading_deg = 84.0,
    .alt_m = 10000.0,
    .lat_deg = 37.4161389,  // KNUQ
    .lon_deg = -122.0491389
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

int check_request (char* host, char* service, int cli_flags, char* cli_in_type, char* cli_out_type, int* track_interval){
    printf("client request from %s:%s\n", host,service);
    printf("    flags:    %x\n", cli_flags);
    printf("    in:       %s\n", cli_in_type);
    printf("    out:      %s\n", cli_out_type);
    printf("    interval: %d\n", *track_interval);

    int ret = 0;

    if (cli_in_type && strcmp(cli_in_type, SIMPLE_TRACK_TYPE) != 0){
        printf("unknown outbound track type: %s\n", cli_in_type);
        ret |= UNKNOWN_DATA;
    }
    if (*track_interval < 500 || *track_interval > 60000) {
        printf("requested interval %d msec out of range\n", *track_interval);
        ret |= UNSUPPORTED_INTERVAL;
    }
    
    printf( (ret == 0) ? "accepted.\n" : "rejected.\n");
    return ret;
}

int write_data(databuf_t* db, int pos) {
    update_position(&track);

    pos = race_write_short(db,pos,1);  // we only send one track (for now)
    pos = race_write_simple_track(db, pos,
                             track.id, track.time_msec, track.lat_deg, track.lon_deg, 
                             track.alt_m, track.heading_deg, track.speed_m_sec);
    return pos;
}

int read_data (databuf_t* db, int pos) {
    char id[MAX_ID_LEN];
    epoch_msec_t time_msec;
    double lat_deg, lon_deg, alt_m, heading_deg, speed_m_sec;
    short n_tracks = 0;

    pos = race_read_short(db,pos,&n_tracks); // the number of tracks we received in this message
    printf("received %d tracks from client:\n", n_tracks);    

    for (int i=0; i<n_tracks; i++) {
        pos = race_read_simple_track(db, pos, 
                                id, sizeof(id), &time_msec, &lat_deg, &lon_deg, 
                                &alt_m, &heading_deg, &speed_m_sec);
        if (pos <= 0){
            fprintf(stderr, "error reading track: %d\n", i);                        
        } else {
            printf("   %d: %s, t=%lld, lat=%f°, lon=%f°, alt=%f m, hdg=%f°, spd=%f m/sec\n", 
                i, id, time_msec, lat_deg, lon_deg, alt_m, heading_deg, speed_m_sec);
        }
    }
    return pos;
}


//--- the test driver

local_context_t context = {
    .port = DEFAULT_PORT,
    .interval_msec = 5000,
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
    return !race_interval_threaded(&context);
}