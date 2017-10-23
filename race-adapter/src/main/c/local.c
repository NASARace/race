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
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>

#include "race_internal.h"

static local_endpoint_t *initialize_local(local_context_t *context) {
    const char *err_msg;

    int fd = race_server_socket(context->port, &err_msg);
    if (fd < 0) {
        context->error("failed to open socket (%s)\n", err_msg);
        return NULL;
    }

    local_endpoint_t *local = malloc(sizeof(local_endpoint_t));
    local->fd = fd;
    local->is_non_blocking = false;
    local->db = race_create_databuf(MAX_MSG_LEN);

    return local;
}

static bool send_data(local_context_t *context, local_endpoint_t *local,
                      remote_endpoint_t *remote) {
    const char *err_msg;
    databuf_t *db = local->db;

    int n_tracks = context->begin_send_data();
    if (n_tracks > 0) {
        int pos = race_begin_write_data(db, SERVER_ID);
        int n = 0;
        for (; n < n_tracks; n++) {
            pos = context->write_send_data(db, pos, n);
            if (pos <= 0) {
                context->warning("too many outbound tracks %d\n", n);
                break;
            }
        }
        race_end_write_data(db, pos, n);
        context->end_send_data(n);
        if (n > 0) {
            if (sendto(local->fd, db->buf, db->pos, 0, remote->addr, remote->addrlen) < 0) {
                context->error("sending track data failed (%s)", strerror(errno));
                return false;
            }
        }
    }
    return true;
}

static void send_stop(local_context_t *context, local_endpoint_t *local,
                      remote_endpoint_t *remote) {
    const char *err_msg;
    databuf_t *db = local->db;

    race_write_stop(db, local->id);
    if (sendto(local->fd, db->buf, db->pos, 0, remote->addr, remote->addrlen) < 0) {
        context->error("sending local stop failed (%s)", strerror(errno));
    }
}

static void local_terminated(local_context_t *context, local_endpoint_t *local) {
    context->info("local terminating\n");

    if (close(local->fd) < 0) {
        context->error("closing socket failed (%s)", strerror(errno));
    }
}

//--- exported functions

/*
 * we send data at a fixed interval and poll the remote synchronously before each send
 */
bool race_interval_poll(local_context_t *context) {
    const char *err_msg;

    if (context == NULL) {
        perror("no local context");
        return false;
    }

    local_endpoint_t *local = initialize_local(context);
    if (local != NULL) {
        while (!context->stop_local) { // outer loop - remote connection
            remote_endpoint_t *remote = wait_for_remote(context, local); 
            if (remote != NULL) {
                local->is_non_blocking = race_set_nonblocking(
                    local->fd, &err_msg); // so that we can poll remote messages
                if (!local->is_non_blocking && (context->flags & DATA_RECEIVER)) {
                    context->warning("cannot receive data from remote, socket is blocking (%s)\n",
                                     err_msg);
                }

                while (!remote->is_stopped && !context->stop_local) { // inner loop - remote data exchange
                    poll_messages(context, local, remote); // this might change remote state

                    if (!remote->is_stopped) {
                        if (!send_data(context, local, remote)) {
                            break;
                        }
                        race_sleep_msec(context->interval_msec);
                    }
                }

                if (context->stop_local && !remote->is_stopped) {
                    send_stop(context, local, remote);
                }
                free(remote);
            }
        }
    }

    local_terminated(context, local);
    free(local);
    return true;
}

/*
 * send at a fixed interval and receive from a thread that blocks
 */
bool race_interval_threaded(local_context_t *context) {
    const char *err_msg;

    if (context == NULL) {
        perror("no local context");
        return false;
    }

    local_endpoint_t *local = initialize_local(context);
    if (local != NULL) {
        while (!context->stop_local) { // outer loop - remote connection
            remote_endpoint_t *remote = wait_for_remote(context, local);
            if (remote != NULL) {
                pthread_t receiver;
                threadargs_t args = { context,local,remote };
                int rc = pthread_create( &receiver, NULL, receive_messages_thread, &args);
                if (rc) {
                    context->error("failed to create receiver thread (%s)\n", strerror(rc));
                    break;
                }

                while (!remote->is_stopped && !context->stop_local) {  // inner loop - remote data exchange
                    if (!remote->is_stopped) {
                        if (!send_data(context, local, remote)) {
                            break;
                        }
                        race_sleep_msec(local->interval_msec);
                    }
                }

                if (context->stop_local && !remote->is_stopped) {
                    send_stop(context, local, remote);
                }

                pthread_cancel(receiver);
                pthread_join(receiver,NULL);
                free(remote);
            }
        }
    }

    local_terminated(context, local);
    free(local);
    return true;
}