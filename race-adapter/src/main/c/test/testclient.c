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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <stdbool.h>
#include <signal.h>
#include <errno.h>
#include <inttypes.h>

#include "../race.h"
#include "testtrack.h"

static bool stop = false;

void sig_handler (int sig){
    switch (sig) {
        case SIGINT:       // ctrl-c termination by user
            stop = true;
            break;
    }
 }

/*
 * this is only meant for testing purposes
 */

int main( int argc, char** argv ){
    char* host = (argc > 1) ? argv[1] : "127.0.0.1";
    char* port = (argc > 2) ? argv[2] : DEFAULT_SERVER_PORT;
    int interval = (argc > 3) ? atoi(argv[3]) : 2000;  // in msec

    // set SIGINT handler without retry
    struct sigaction sa;
    sa.sa_handler = sig_handler;
    sa.sa_flags = 0; // don't restart
    sigaction(SIGINT, &sa, NULL);

    const char* err_msg;
    struct sockaddr* serveraddr;
    socklen_t addrlen;

    int fd = race_client_socket(host,port, &serveraddr,&addrlen, &err_msg);
    if (fd < 0){
        fprintf(stderr,"error opening socket (%s)\n", err_msg);
        return 1;
    }

    databuf_t* db = race_create_databuf(MAX_MSG_LEN);

    //--- send client request
    printf("sending request to server %s:%s\n", host,port);
    race_write_request(db, DATA_RECEIVER, SIMPLE_TRACK_PROTOCOL, race_epoch_millis(), interval);
    if (sendto(fd, db->buf, db->pos, 0, serveraddr, addrlen) < 0){
        fprintf(stderr,"sending CLIENT_REQUEST failed (%s)\n", strerror(errno));
        return 1;
    }

    epoch_millis_t sim_millis;
    int client_id;
    int server_flags;
    int server_interval;

    //--- receive server response
    printf("waiting for server response..\n");
    db->pos = recvfrom( fd, db->buf, db->capacity, 0, serveraddr, &addrlen);
    if (db->pos <= 0) {
        fprintf(stderr, "failed to receive server response: %s\n", strerror(errno));
        exit(EXIT_FAILURE);
    }
    if (race_is_accept(db)) {
        if (race_read_accept (db, &server_flags, &sim_millis, &server_interval, &client_id, &err_msg) <= 0){
            fprintf(stderr, "error reading SERVER_RESPONSE: %s\n", err_msg);
            exit(EXIT_FAILURE);
        } else {
            printf("server response: client_id=%x, sim_millis=%"PRId64", interval=%d msec\n", client_id, sim_millis, server_interval);
        }    
    } else if (race_is_reject(db)) {
        int reason;
        if (race_read_reject(db, &reason, &err_msg) <= 0){
            fprintf(stderr, "error reading SERVER_REJECT (%s)\n", err_msg);
            exit(EXIT_SUCCESS);
        } else {
            printf("server reject, reason: %x\n", reason);
            exit(EXIT_FAILURE);
        }
    } else {
        fprintf(stderr, "no valid server response\n");
        exit(EXIT_FAILURE);
    }

    //--- loop while server is running or until user interrupts
    while (!stop) {
        printf("waiting for server data..\n");
        db->pos = recvfrom( fd, db->buf, db->capacity, 0, serveraddr, &addrlen);
        if (db->pos > 0) {
            if (race_is_stop(db)) {
                printf("received stop\n");
                break;

            } else if (race_is_data(db)) {
                int sender_id;
                epoch_millis_t send_time;
                int pos = race_read_data_header(db, &sender_id, &send_time, &err_msg);
                if ( pos <= 0) {
                    fprintf(stderr, "error reading tracks header: %s\n", err_msg);
                } else {
                    short data_msg_type;
                    pos = race_read_short(db,pos,&data_msg_type);
                    if (data_msg_type == TRACK_MSG) {
                        short n_tracks;
                        pos = race_read_short(db,pos,&n_tracks);
                        printf("received %d tracks\n", n_tracks);
                        for (int i=0; i<n_tracks; i++) {
                            char id[MAX_ID_LEN];
                            int msg_ord;
                            int flags;
                            epoch_millis_t track_millis;
                            double lat_deg, lon_deg, alt_m, heading_deg, speed_m_sec, vr_m_sec;

                            pos = race_read_track_data(db, pos, id, sizeof(id), &msg_ord, &flags, &track_millis,
                                                       &lat_deg, &lon_deg, &alt_m,
                                                       &heading_deg, &speed_m_sec, &vr_m_sec);
                            if (pos <= 0){
                                fprintf(stderr, "error reading track: %s\n", err_msg);                        
                            } else {
                                printf("   %d: %s, ord=%d, flags=0x%X, t=%"PRId64", lat=%f°, lon=%f°, alt=%f m, hdg=%f°, spd=%f m/sec, vr=%f m/sec\n",
                                    i, id, msg_ord, flags, track_millis, lat_deg, lon_deg, alt_m, heading_deg, speed_m_sec, vr_m_sec);
                            }
                        }
                    } else if (data_msg_type == PROXIMITY_MSG) {
                        printf("ignoring proximity data message\n");
                    } else {
                        fprintf(stderr, "unknown data message type: %d\n", data_msg_type);
                    }
                }
            }
        } else {
            if (errno != EINTR) {
                fprintf(stderr, "error while waiting for server tracks (%s)\n", strerror(errno));
            } else {
                // notify server about shut down
                race_write_stop(db, client_id);
                if (sendto(fd, db->buf, db->pos, 0, serveraddr, addrlen) < 0){
                    fprintf(stderr, "error sending client_stop (%s)\n", strerror(errno));                    
                }
            }
            stop = true;
        }     
    }

    printf("client terminating\n");
    close( fd );
}
