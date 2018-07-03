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
 * RACE system message read/write support
 */

#include <string.h>
#include <stdio.h>

#include "race.h"

/*
     struct msg_header {
         short msg_type;      // numeric id for message type (see messages.h)
         short msg_length; // header and data - must equal read size
         int sender_id;       // 0 for server
         long  time_millis;     // send epoch in msec
     }
 */

#define HEADER_LEN 16

//--- internal routines

static int is_msg (databuf_t* db, short expect_id, int expect_len) {
    short msg_id;
    int read_len = db->pos;

    if (read_len > 0 && (expect_len == NO_FIXED_MSG_LEN || read_len == expect_len)) {
        race_peek_short(db, 0, &msg_id);
        return msg_id == expect_id;
    } else {
        return 0;
    }
}

static int write_header (databuf_t* db, short msg_id, int msg_len, int sender) {
    int pos = race_reset_databuf(db);
    // this is a header-only message
    pos = race_write_short(db, pos, msg_id);  // msg type
    pos = race_write_short(db, pos, msg_len);  // length of message in bytes
    pos = race_write_int(db, pos, sender);
    pos = race_write_long(db, pos, race_epoch_millis());
    return pos;
}

static void set_msg_len (databuf_t* db, short msg_len) {
    race_set_short(db,2, msg_len);
}

static int read_header (databuf_t* db, short id, int check_len, 
                        int* sender, epoch_millis_t* time_millis, const char** err_msg) {
    short msg_id;
    int read_len = db->pos;

    if (err_msg != NULL) *err_msg = "";

    if (check_len == NO_FIXED_MSG_LEN || read_len == check_len) { // check against fixed msg type length (if specified)
        int pos = race_read_short(db, 0, &msg_id);
        if (msg_id == id) {
            short msg_len;
            pos = race_read_short(db, pos, &msg_len);
            if (msg_len == read_len) { // check stored msg length against read length
                pos = race_read_int(db,pos,sender);
                pos = race_read_long(db,pos,time_millis);  // save timestamp (if requested)
                return pos;

            } else {
                *err_msg = "inconsistent header (message length does not match received bytes)";
                return 0;
            }

        } else {
            *err_msg = "wrong message type";
            return 0;
        }
    } else {
        *err_msg = "wrong message length";
        return 0;
    }
}


//--- REQUEST_MSG

/*
    this is sent from client to server and initiates the communication

    struct {
        struct msg_header;
        int client_flags;            // client capabilities
        string schema;               // schema that defines exchanged data
        epoch_millis_t* sim_millis;      // requested simulation time
        int request_interval_millis;   // requested track update interval in msec
    }
*/

int race_write_request (databuf_t* db, int flags, char* schema, epoch_millis_t sim_millis, int interval_millis) {
    int pos =  write_header(db, REQUEST_MSG, NO_FIXED_MSG_LEN, NO_ID);
    pos = race_write_int(db, pos, flags);
    pos = race_write_string(db, pos, schema, strlen(schema));
    pos = race_write_long(db,pos,sim_millis);
    pos = race_write_int(db,pos,interval_millis);
    set_msg_len(db,pos);
    return pos;
}

int race_is_request (databuf_t* db) {
    return is_msg(db, REQUEST_MSG, NO_FIXED_MSG_LEN);
}

int race_read_request (databuf_t* db, epoch_millis_t* time_millis, 
                         int* cli_flags, 
                         char* schema, int max_schema_len,
                         epoch_millis_t* sim_millis, int* interval_millis, const char** err_msg) {
    int pos = read_header(db, REQUEST_MSG, NO_FIXED_MSG_LEN, NULL, time_millis, err_msg);
    if (pos > 0){
        pos = race_read_int(db,pos,cli_flags);
        pos = race_read_strncpy(db,pos, schema, max_schema_len);
        pos = race_read_long(db,pos, sim_millis);    
        return race_read_int(db, pos, interval_millis);
    } else {
        return 0;
    }
}

//--- ACCEPT_MSG

/*
    this is the positive server response for a REQUEST_MSG

    struct {
        struct msg_header;
        int server_flags;              // server capabilities
        epoch_millis_t* sim_millis;    // server simulation time (can differ from request)
        int server_interval_millis;    // server update interval preference (this can differ from request)
        int client_id;                 // assigned by server
    }

*/

#define ACCEPT_LEN HEADER_LEN + 20

int race_write_accept (databuf_t* db, int flags, epoch_millis_t sim_millis, int interval_millis, int client_id) {
    int pos =  write_header(db, ACCEPT_MSG, ACCEPT_LEN, SERVER_ID);
    pos = race_write_int(db,pos,flags);
    pos = race_write_long(db,pos,sim_millis);
    pos = race_write_int(db,pos,interval_millis);
    return race_write_int(db, pos, client_id);
}

int race_is_accept (databuf_t* db) {
    return is_msg(db, ACCEPT_MSG, ACCEPT_LEN);
}

int race_read_accept (databuf_t* db, int* flags, epoch_millis_t* sim_millis, int* interval_millis, int* client_id, const char** err_msg) {
    int pos = read_header(db, ACCEPT_MSG, ACCEPT_LEN, NULL, NULL, err_msg);
    if (pos > 0){
        pos = race_read_int(db,pos, flags);
        pos = race_read_long(db,pos, sim_millis);
        pos = race_read_int(db,pos, interval_millis);
        return race_read_int(db, pos, client_id);
    } else {
        return 0;
    }
}

//--- REJECT_MSG

/*
    struct {
        struct msg_header;
        int reject_reason
    }
*/

#define REJECT_LEN HEADER_LEN + 4

int race_write_reject (databuf_t* db, int reason) {
    int pos = write_header(db, REJECT_MSG, REJECT_LEN, SERVER_ID);
    pos = race_write_int(db,pos,reason);
    return pos;
}

int race_is_reject (databuf_t* db) {
    return is_msg(db, REJECT_MSG, REJECT_LEN);
}

int race_read_reject (databuf_t* db, int* reason, const char** err_msg) {
    int pos = read_header(db, REJECT_MSG, REJECT_LEN, NULL, NULL, err_msg);
    if (pos > 0){
        pos = race_read_int(db,pos, reason);
        return pos;
    } else {
        return 0;
    }
}

//--- STOP_MSG

/*
    can be sent by client at any time to indicate it doesn't want to receive any more messages
    this is a protocol terminator

    struct {
        struct msg_header;
    }
*/

#define STOP_MSG_LEN HEADER_LEN

int race_write_stop (databuf_t* db, int sender_id) {
    return write_header(db, STOP_MSG, STOP_MSG_LEN, sender_id);
}

int race_is_stop (databuf_t* db) {
    return is_msg(db, STOP_MSG, STOP_MSG_LEN);
}

int race_read_stop (databuf_t* db, int *sender_id, epoch_millis_t* time_millis, const char** err_msg) {
    return read_header(db, STOP_MSG, STOP_MSG_LEN, sender_id, time_millis, err_msg);
}

//--- PAUSE / RESUME

/*
    can be sent by server at any time to pause and resume execution

    struct {
        struct msg_header;
    }
*/


#define PAUSE_MSG_LEN HEADER_LEN

int race_write_pause (databuf_t* db, int sender_id) {
    return write_header(db, PAUSE_MSG, PAUSE_MSG_LEN, sender_id);
}

int race_is_pause (databuf_t* db) {
    return is_msg(db, PAUSE_MSG, PAUSE_MSG_LEN);
}

int race_read_pause (databuf_t* db, int *sender_id, epoch_millis_t* time_millis, const char** err_msg) {
    return read_header(db, PAUSE_MSG, PAUSE_MSG_LEN, sender_id, time_millis, err_msg);
}

#define RESUME_MSG_LEN HEADER_LEN

int race_write_resume (databuf_t* db, int sender_id) {
    return write_header(db, RESUME_MSG, RESUME_MSG_LEN, sender_id);
}

int race_is_resume (databuf_t* db) {
    return is_msg(db, RESUME_MSG, RESUME_MSG_LEN);
}

int race_read_resume (databuf_t* db, int *sender_id, epoch_millis_t* time_millis, const char** err_msg) {
    return read_header(db, RESUME_MSG, RESUME_MSG_LEN, sender_id, time_millis, err_msg);
}

//--- DATA

/*
    can be sent both ways
    this is a variable length message with a generic, application specific payload that is written/read
    by context callbacks

    struct {
        struct msg_header;
        void* payload // app specific
    }
*/

int race_is_data (databuf_t* db) {
    return is_msg(db, DATA_MSG, 0);    
}


int race_begin_write_data (databuf_t* db, int sender_id) {
    return write_header(db, DATA_MSG, 0, sender_id); // length will be set by race_end_write_data
}

// writing track data is done in the context (we don't know the type)

int race_end_write_data (databuf_t* db, int pos) {
    if (pos > 0){
        race_set_short(db, 2, (short)pos); // fill in message length
    }
    return pos;
}


int race_read_data_header (databuf_t* db, int *sender_id, epoch_millis_t* time_millis, const char** err_msg) {
    return read_header(db, DATA_MSG, NO_FIXED_MSG_LEN, sender_id, time_millis, err_msg);
}

// reading track data is done in the context (we don't know the type)
