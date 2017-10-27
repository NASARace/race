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

#include <errno.h>
#include "string.h"

#include "race_internal.h"

#define MAX_TYPE_LEN 64

static int n_remote = 0;

remote_endpoint_t* wait_for_remote (local_context_t* context, local_endpoint_t* local) {
    const char* err_msg;
    databuf_t* db = local->db;

    // make sure we are in blocking mode before we wait for a remote
    local->is_non_blocking = !race_set_blocking(local->fd, &err_msg);
    if (local->is_non_blocking){
        context->error("cannot put socket into blocking mode (%s)\n", err_msg);
        return NULL;
    }

    socklen_t addrlen;
    struct sockaddr* src_addr = race_create_sockaddr(&addrlen);

    context->info("waiting for request..\n");
    int nread = recvfrom( local->fd, db->buf, db->capacity, 0, src_addr, &addrlen);
    if (nread > 0) {
        db->pos = nread;
        
        int req_flags = 0;
        int req_interval_msec = 0;
        epoch_msec_t time_sent = 0;
        char req_out_type[MAX_TYPE_LEN];
        char req_in_type[MAX_TYPE_LEN];

        if (!race_read_request(local->db, &req_flags, req_in_type, req_out_type, MAX_TYPE_LEN, &req_interval_msec, &time_sent, &err_msg)){
            context->error("error reading remote request (%s)\n", err_msg);
            free(src_addr);
            return NULL;
        }

        // who are we talking to
        char client_host[NI_MAXHOST];
        char client_service[NI_MAXSERV];
        getnameinfo(src_addr,addrlen, client_host,sizeof(client_host), client_service,sizeof(client_service),NI_NUMERICHOST);
        
        // check possible reasons for rejection
        int reject = context->check_request(client_host, client_service, req_flags, req_in_type, req_out_type, &req_interval_msec);
        if (reject) {
            context->info("remote rejected for reason %x\n", reject);
            race_write_reject(db, reject);
            if (sendto(local->fd, db->buf, db->pos, 0, src_addr, addrlen) < 0) {
                context->error("sending local response failed (%s)", strerror(errno));
            }
            free(src_addr);
            return NULL;
        }

        // remote is accepted, set local state accordingly 
        local->interval_msec = req_interval_msec; 

        int remote_id = ++n_remote;
        race_write_accept(db, context->flags, local->interval_msec, remote_id);
        if (sendto(local->fd, db->buf, db->pos, 0, src_addr, addrlen) < 0) {
            context->error("sending local accept failed (%s)", strerror(errno));
            free(src_addr);
            return NULL;
        }

        remote_endpoint_t* remote = malloc(sizeof(remote_endpoint_t));
        remote->addr = src_addr;
        remote->addrlen = addrlen;
        remote->id = remote_id;
        remote->time_request = remote->time_last = time_sent;
        remote->is_stopped = false;
        return remote;

    } else {
        if (!context->stop_local) {
            context->error("reading remote request failed (%s)\n", strerror(errno));
        }
        return NULL;
    }
}