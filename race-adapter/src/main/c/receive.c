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
#include <pthread.h>
#include <string.h>
#include <pthread.h>
#include <stdio.h>

#include "race_internal.h"

void receive_message(local_context_t *context, local_endpoint_t *local, remote_endpoint_t *remote) {
    databuf_t db = DATABUF(MAX_MSG_LEN);
    const char *err_msg;
    int remote_id;
    epoch_msec_t send_time;

    int n_read = recvfrom(local->fd, db.buf, db.capacity, 0, remote->addr, &remote->addrlen);
    if (n_read > 0) {
        db.pos = n_read;

        if (race_is_stop(&db)) {
            if (race_read_stop(&db, &remote_id, NULL, &err_msg) && remote_id == remote->id) {
                remote->is_stopped = true;
            }

        } else if (race_is_data(&db)) {
            if (context->flags & DATA_RECEIVER) {
                int pos = race_read_data_header(&db, &remote_id, &send_time, &err_msg);
                if (pos && remote_id == remote->id && send_time >= remote->time_last) {
                    remote->time_last = send_time;
                    context->read_data(&db,pos);
                } else {
                    context->warning("ignoring out-of-order message from remote %x (%s)\n", remote_id, err_msg);
                }
            } else {
                context->warning("local is ignoring track messages\n");
            }
        } else {
            context->warning("received unknown message\n");
        }
    } else {
        context->error("polling remote failed (%s)\n", strerror(errno));
    }
}

void* receive_messages_thread(void* args) {
    local_context_t *context = ((threadargs_t*)args)->context;
    local_endpoint_t *local = ((threadargs_t*)args)->local;
    remote_endpoint_t *remote = ((threadargs_t*)args)->remote;

    context->info("receiver thread started\n");

    while (!remote->is_stopped && !context->stop_local) {
        receive_message(context, local, remote);
        // TODO - shall we safeguard against starvation of other threads here?
    }

    context->info("receiver thread terminated\n");
    pthread_exit(NULL);
}

void poll_messages (local_context_t* context, local_endpoint_t* local, remote_endpoint_t* remote) {
    const char* err_msg;
    int n_msgs = 0;
    
    local->is_non_blocking = race_set_nonblocking(local->fd, &err_msg); // so that we can poll remote messages
    if (!local->is_non_blocking && (context->flags & DATA_RECEIVER)) {
        context->warning("cannot receive data from remote, socket is blocking (%s)\n", err_msg);
    }

    if (local->is_non_blocking){
        while (race_check_available(local->fd, &err_msg) > 0 && n_msgs < 42) {
            receive_message(context, local, remote);
        }
    }
}