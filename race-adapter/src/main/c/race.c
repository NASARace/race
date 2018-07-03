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
 * this file contains the toplevel raceserver/client functions called from application
 * specific code
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
#include <inttypes.h>

#include "race_internal.h"

//--- internal functions

static local_endpoint_t *initialize_local_server (local_context_t *context) {
    const char *err_msg;

    int fd = race_server_socket(context->port, &err_msg);
    if (fd < 0) {
        context->error("failed to open server socket (%s)\n", err_msg);
        return NULL;
    }

    local_endpoint_t *local = calloc(1,sizeof(local_endpoint_t));
    local->fd = fd;
    local->is_non_blocking = false;
    local->db = race_create_databuf(MAX_MSG_LEN);

    return local;
}

static local_endpoint_t *initialize_local_client (local_context_t *context, remote_endpoint_t *remote) {
    const char *err_msg;
    struct sockaddr* serveraddr;
    socklen_t addrlen;
    int fd = -1;
    
    while (fd < 0 && !context->stop_local){
        fd = race_client_socket(context->host, context->port, &serveraddr,&addrlen, &err_msg);
        // NOTE - this is a UDP socket - getting a fd does not mean somebody is listening, only that the server was found
        if (fd < 0) {
            if (context->connect_interval_millis == 0) {
                context->error("failed to open client socket to %s:%s (%s)\n", context->host, context->port, err_msg);
                return NULL;
            } else {
                // TODO - we could check the fd / errno value here
                race_sleep_millis(context->connect_interval_millis);
            }
        }
    }
    
    remote->addr = serveraddr;
    remote->addrlen = addrlen;

    local_endpoint_t *local = calloc(1,sizeof(local_endpoint_t));
    local->fd = fd;
    local->is_non_blocking = false;
    local->db = race_create_databuf(MAX_MSG_LEN);

    return local;
}

/*
 * send a already assembled message
 */
static bool send_assembled_message (local_context_t *context, local_endpoint_t *local, remote_endpoint_t* remote) {
    databuf_t *db = local->db;
    if (sendto(local->fd, db->buf, db->pos, 0, remote->addr, remote->addrlen) < 0) {
        context->error("sending message failed (%s)", strerror(errno));
        return false;
    } else {
        return true;
    }
}

static bool send_request (local_context_t *context, local_endpoint_t *local, remote_endpoint_t* remote) {
    context->write_request(local->db,0);
    return send_assembled_message(context,local,remote);
}

/*
 * assemble and send a DATA message
 */
static bool send_data(local_context_t *context, local_endpoint_t *local,
                      remote_endpoint_t *remote) {
    databuf_t *db = local->db;

    int pos = race_begin_write_data(db, local->id);
    pos = context->write_data(db,pos);  // acquire data via app specific callback
    if (pos >= 0) {
        pos = race_end_write_data(db,pos);
        if (pos > 0) {
            if (sendto(local->fd, db->buf, pos, 0, remote->addr, remote->addrlen) < 0) {
                context->error("sending track data failed (%s)", strerror(errno));
                return false;
            }
        }
    } else {
        context->warning("no data payload written");  // ? should this be a warning ?
    }

    return true;
}

/*
 * send a STOP message
 */
static void send_stop(local_context_t *context, local_endpoint_t *local,
                      remote_endpoint_t *remote) {
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

static int n_remote = 0; // number of remote clients we are connected to

static void set_time_diff (local_context_t* context, epoch_millis_t sim_millis) {
    long time_diff = race_epoch_millis() - sim_millis;  // TODO - this should acquire sim time from the context
    if (labs(time_diff) > MAX_TIME_DIFF) {
        context->info("adapting simulation time by %d sec\n", time_diff / 1000);
        context->time_diff = time_diff;
    }
}

/*
 * block until we get a REQEUST message, then use context callbacks to determine if
 * we send a ACCEPT or REJECT message in response
 * 
 * TODO - this should handle schema versions
 */
static remote_endpoint_t* wait_for_request (local_context_t* context, local_endpoint_t* local) {
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

    context->info("waiting for request on %s:%s\n", context->host,context->port);
    int nread = recvfrom( local->fd, db->buf, db->capacity, 0, src_addr, &addrlen);
    if (nread > 0) {
        db->pos = nread;
        
        int req_flags = 0;
        int interval_millis = 0;
        epoch_millis_t sim_millis = 0;
        epoch_millis_t time_sent = 0;
        char req_schema[MAX_SCHEMA_LEN];

        if (!race_read_request(local->db, &time_sent, &req_flags, req_schema, MAX_SCHEMA_LEN, &sim_millis, &interval_millis, &err_msg)){
            context->error("error reading remote request (%s)\n", err_msg);
            free(src_addr);
            return NULL;
        }

        // who are we talking to
        char client_host[NI_MAXHOST];
        char client_service[NI_MAXSERV];
        getnameinfo(src_addr,addrlen, client_host,sizeof(client_host), client_service,sizeof(client_service),NI_NUMERICHOST);
        
        // check possible reasons for rejection (note this could also change sim_millis and interval_millis)
        int reject = context->check_request(client_host, client_service, req_flags, req_schema,
                                            &sim_millis, &interval_millis);
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
        local->interval_millis = interval_millis;
        
        // check if we have to apply a time difference
        set_time_diff(context, sim_millis);

        int remote_id = ++n_remote;
        race_write_accept(db, context->flags, sim_millis, local->interval_millis, remote_id);
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

static bool wait_for_response (local_context_t *context, local_endpoint_t *local, remote_endpoint_t* remote){
    const char* err_msg;
    databuf_t *db = local->db;
    epoch_millis_t sim_millis;
    int client_id;
    int server_flags;
    int interval_millis;

    int n_read = recvfrom( local->fd, db->buf, db->capacity, 0, remote->addr, &remote->addrlen);

    if (n_read <= 0) {
        if (context->connect_interval_millis == 0) {
            context->error("failed to receive server response: %s\n", strerror(errno));
        }
        return false;
    }

    db->pos = n_read;
    if (race_is_accept(db)) {
        if (race_read_accept (db, &server_flags, &sim_millis, &interval_millis, &client_id, &err_msg) <= 0){
            context->error("error reading SERVER_RESPONSE: %s\n", err_msg);
            return false;

        } else { // accepted
            context->info("server accept: client_id=%x, sim_millis=%"PRId64", interval=%d msec\n", client_id, sim_millis, interval_millis);
            set_time_diff(context, sim_millis);
            local->interval_millis = interval_millis;
            return true;
        }    
    } else if (race_is_reject(db)) {
        int reason;
        if (race_read_reject(db, &reason, &err_msg) <= 0){
            context->error("error reading SERVER_REJECT (%s)\n", err_msg);
            return false;
        } else {
            context->info("server reject, reason: %x\n", reason);
            return false;
        }
    } else {
        context->error("no valid server response\n");
        return false;
    }
}

/*
 * wait blocking until we receive a message from the remote endpoint, process system messages
 * and pass data messages to the application specific context callback
 */
static void receive_message(local_context_t *context, local_endpoint_t *local, remote_endpoint_t *remote) {
    databuf_t db = DATABUF(MAX_MSG_LEN);
    const char *err_msg;
    int remote_id;
    epoch_millis_t send_time;

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
                if (pos == 0) {
                  context->error("received malformed message from remote %x (%s)\n", remote_id, err_msg);
                } else if (remote_id != remote->id) {
                  context->warning("ignoring message from unknown remote %x (expected %x)\n", remote_id, remote->id);
                } else if (send_time < remote->time_last) {
                  context->warning("ignoring out-of-order message from remote %x (%"PRId64" < %"PRId64")\n", remote_id, send_time, remote->time_last);
                } else {
                    remote->time_last = send_time;
                    context->read_data(&db,pos);
                }
            } else {
                context->warning("local is ignoring track messages\n");
            }

        } else if (race_is_pause(&db)) {
            if (race_read_pause(&db, &remote_id, NULL, &err_msg) && remote_id == remote->id) {
                if (context->connection_paused) context->connection_paused();
            }

        } else if (race_is_resume(&db)) {
            if (race_read_resume(&db, &remote_id, NULL, &err_msg) && remote_id == remote->id) {
                if (context->connection_resumed) context->connection_resumed();
            }
            
        } else {
            context->warning("received unknown message\n");
        }
    } else {
        context->error("polling remote failed (%s)\n", strerror(errno));
    }
}

/*
 * thread function that loops receive_message() calls until one of the end points is stopped
 */
static void* receive_messages_thread(void* args) {
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

/*
 * poll remote messages (non-blocking)
 */
static void poll_messages (local_context_t* context, local_endpoint_t* local, remote_endpoint_t* remote) {
    if (local->is_non_blocking){
        const char* err_msg;
        int n_msgs = 0;

        while (race_check_available(local->fd, &err_msg) > 0 && n_msgs < MAX_POLLED_MSGS) {
            receive_message(context, local, remote);
            n_msgs++;
        }
    }
}

//--- exported functions (to be used by application specific code)

/*
 * send data at a fixed interval and poll the remote endpoint synchronously before each send
 */
static bool run_connection_polling (local_context_t *context, local_endpoint_t* local, remote_endpoint_t *remote) {
    const char *err_msg;

    if (context->connection_started) context->connection_started();

    local->is_non_blocking = race_set_nonblocking(local->fd, &err_msg); // so that we can poll remote messages
    if (!local->is_non_blocking && (context->flags & DATA_RECEIVER)) {
        context->warning("cannot receive data from remote, socket is blocking (%s)\n",err_msg);
    }

    while (!remote->is_stopped && !context->stop_local) { // inner loop - remote data exchange
        poll_messages(context, local, remote); // this might change remote state

        if (!remote->is_stopped) {
            if (!send_data(context, local, remote)) {
                break;
            }
            race_sleep_millis(context->interval_millis);
        }
    }

    if (context->stop_local && !remote->is_stopped) {
        send_data(context,local,remote);
        send_stop(context, local, remote);
    }
    if (context->connection_terminated) context->connection_terminated();

    return true;
}

static bool run_connection_threaded (local_context_t *context, local_endpoint_t* local, remote_endpoint_t *remote) {
    pthread_t receiver;
    threadargs_t args = { context,local,remote };
    int rc = pthread_create( &receiver, NULL, receive_messages_thread, &args);
    if (rc) {
        context->error("failed to create receiver thread (%s)\n", strerror(rc));
        return false;
    }

    if (context->connection_started) context->connection_started();

    while (!remote->is_stopped && !context->stop_local) {  // inner loop - remote data exchange
        if (!remote->is_stopped) {
            if (!send_data(context, local, remote)) {
                break;
            }
            race_sleep_millis(local->interval_millis);
        }
    }

    if (context->stop_local && !remote->is_stopped) {
        send_data(context,local,remote); // send last track update with dropped status
        send_stop(context, local, remote);
    }

    pthread_cancel(receiver);  // TODO - shall we use less harsh measures to terminate?
    pthread_join(receiver,NULL);  // this blocks until the thread is finished

    if (context->connection_terminated) context->connection_terminated();
    return true;
}


/*
 * high level librace adapter functions
 * 
 * send data at a fixed interval and receive remote data async (when it is received),
 * both without explicit synchronization/trigger from host program
 */

// local is started before remote, local waits for and accepts/rejects remote request
bool race_server (local_context_t *context) {
    if (context == NULL) {
        perror("no local context");
        return false;
    }

    local_endpoint_t *local = initialize_local_server (context);
    if (local != NULL) {
        while (!context->stop_local) { // outer loop - remote connection
            remote_endpoint_t *remote = wait_for_request(context, local);
            if (remote != NULL) {
                if (!run_connection_threaded(context,local,remote)) break;
            }
            free(remote);
        }
        local_terminated(context, local);
        free(local);
    }

    return true;
}

static bool establish_connection (local_context_t *context, local_endpoint_t *local, remote_endpoint_t* remote) {
    const char *err_msg;
    databuf_t *db = local->db;

    if (!race_set_rcv_timeout(local->fd,RECV_TIMEOUT_MILLIS,&err_msg)){
        context->error("failed to set response timeout: %s\n", err_msg);
        return false;
    }

    context->write_request(db,0);

    while (!context->stop_local){
        if (!send_assembled_message(context,local,remote)) {
            return false;
        } else {
            if (!wait_for_response(context,local,remote)) {
                if (context->connect_interval_millis > 0) {
                    race_sleep_millis(context->connect_interval_millis);
                } else {
                    return false;
                }
            } else {
                break;
            }
        }
    }
    race_set_rcv_timeout(local->fd,0,&err_msg); // reset read timeout
    return !context->stop_local;
}

// remote is started before local, local sends request and waits for accept/reject
// note that if context->connect_interval_millis > 0 this does not return until
// context->stop_local is set or a connection has been terminated
bool race_client (local_context_t *context) {
    if (context == NULL) {
        perror("no local context");
        return false;
    }

    bool ret = false;
    remote_endpoint_t *remote = (remote_endpoint_t*)calloc(1,sizeof(remote_endpoint_t));
    local_endpoint_t *local = initialize_local_client (context, remote); // blocking until we find host
    if (local != NULL) {
        if (establish_connection(context,local,remote)) {  // blocking until we get response from host
            ret = run_connection_threaded(context,local,remote);
            local_terminated(context, local);
        }
        free(local);
    }
    free(remote);
    return ret;
}