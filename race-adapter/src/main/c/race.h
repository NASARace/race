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
 * race.h - header with exported values, types and functions to let native programs communicate with RACE
 * 
 * Although this conceptually is partitioned into several layers we keep everything in a single header
 * file to reduce the number of files that have to be accessible from the 3rd party project
 */

#ifndef RACE_INCLUDED
#define RACE_INCLUDED

#include <stdbool.h>
#include <stdlib.h>
#include <netdb.h>

/*************************************************************************************************
 * network helpers
 */

int race_client_socket (char* hostname, char* service, struct sockaddr** serveraddr, socklen_t* addrlen, const char** err_msg);
int race_server_socket (char* port, const char** err_msg);

struct sockaddr* race_create_sockaddr(socklen_t* socklen);
bool race_set_nonblocking (int fd, const char** err_msg);
bool race_set_blocking (int fd, const char** err_msg);
int race_check_available (int fd, const char** err_msg);

/*************************************************************************************************
 * time helpers
 */

int race_sleep_msec (int millis);
long race_epoch_msec();


/*************************************************************************************************
 * low level support to serialize/deserialize messages
 * (this is mostly used from custom read_data_msg() and write_data_msg() callback implementations)
 */

// the fixed length data buffer type used to write/read message data
typedef struct {
    unsigned char* buf;  // the actual buffer
    int pos;             // current position in buffer
    int capacity;        // size of allocated buffer
} databuf_t;

#define IS_D64 (sizeof(double) == 8)
#define EMPTY_STRING ""

// not all platforms have htonll and ntohll macros so we roll our own
static inline uint64_t _htonll(long x) {
    return  (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__) ? (((uint64_t)htonl((x) & 0xFFFFFFFF) << 32) | htonl((x) >> 32)) : x;
}
static inline uint64_t _ntohll(long x) {
    return (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__) ? (((uint64_t)ntohl((x) & 0xFFFFFFFF) << 32) | ntohl((x) >> 32)) : x;
}

// check if position is inside buffer
static inline bool race_check_pos(databuf_t *db, int pos) {
    return ((pos >= 0) && (pos < db->capacity));
}

// functions to create, write and read databuf

#define DATABUF(len) { (unsigned char*)alloca(len), 0, len }

databuf_t* race_create_databuf(size_t size); // allocate new buffer
int race_init_databuf(databuf_t *db, unsigned char *buf, int capacity); // initialize buffer from allocated memory
int race_reset_databuf(databuf_t *db);

// set values without advancing db->pos
void race_set_short(databuf_t *db, int pos, short v);

// set values and advance db->pos
int race_write_short(databuf_t *db, int pos, short v);
int race_write_int(databuf_t *db, int pos, int v);
int race_write_long(databuf_t *db, int pos, long v);
int race_write_double(databuf_t *db, int pos, double v);
int race_write_string(databuf_t *db, int pos, char *s, int len);
int race_write_empty_string(databuf_t *db, int pos);

// read values without advancing db->pos
int race_peek_byte(databuf_t *db, int pos, char *v);
int race_peek_short(databuf_t *db, int pos, short *v);
int race_peek_int(databuf_t *db, int pos, int *v);
int race_peek_long(databuf_t *db, int pos, long *v);
int race_peek_double(databuf_t *db, int pos, double *v);

// set values and advance db->pos
int race_read_byte(databuf_t *db, int pos, char *v);
int race_read_short(databuf_t *db, int pos, short *v);
int race_read_int(databuf_t *db, int pos, int *v);
int race_read_long(databuf_t *db, int pos, long *v);
int race_read_double(databuf_t *db, int pos, double *v);
int race_read_strdup(databuf_t *db, int pos, char **s);
int race_read_strncpy(databuf_t *db, int pos, char *dest, int max_len);

void race_hex_dump(databuf_t* db); // for debugging purposes


/*************************************************************************************************
 * high level message message protocol support
 */

 // message types
#define REQUEST_MSG  1
#define ACCEPT_MSG   2
#define REJECT_MSG   3
#define DATA_MSG     4
#define STOP_MSG     5

#define SERVER_ID 0 // the endpoint ansering requests
#define NO_ID -1
// IDs are dynamically assigned with accept response

#define NO_FIXED_MSG_LEN 0

#define MAX_MSG_LEN      1024 // including header, should be <= MTU to avoid IP fragmentation  

int race_write_request (databuf_t* db, int flags, char* in_type, char* out_type, int interval_msec);
int race_is_request (databuf_t* db);
int race_read_request (databuf_t* db, int* flags, char* in_type, char* out_type, int max_type_len, int* interval_msec, long* time_msec, const char** err_msg);

int race_write_accept (databuf_t* db, int flags, int interval_msec, int client_id);
int race_is_accept (databuf_t* db);
int race_read_accept (databuf_t* db, long* time_msec, int* flags, int* interval_msec, int* client_id, const char** err_msg);

int race_write_reject  (databuf_t* db, int reason);
int race_is_reject (databuf_t* db);
int race_read_reject (databuf_t* db, int* reason, const char** err_msg);

int race_write_stop (databuf_t* db, int sender);
int race_is_stop (databuf_t* db);
int race_read_stop (databuf_t* db, int* sender, long* time_msec, const char** err_msg);


//--- application messages

//--- data msg API (can go both ways)
int race_begin_write_data (databuf_t* db, int sender_id);
// writing outbound data payload is done in the context layer (we don't know the concrete type here)
int race_end_write_data (databuf_t* db, int pos);

int race_is_data (databuf_t* db);
int race_read_data_header (databuf_t* db, int* sender, long* time_msec, const char** err_msg);
// reading inbound data payload is done in the context layer (we don't know the concrete type here)


/*************************************************************************************************
 * off-the-shelf data support
 */

// simple_track is a virtual type with minimal track state info

#define SIMPLE_TRACK_TYPE "simple_track"

int race_write_simple_track(databuf_t *db, int pos, char *id, long time_msec,
                       double lat_deg, double lon_deg, double alt_m,
                       double heading_deg, double speed_m_sec);

int race_read_simple_track(databuf_t *db, int pos, char id[], int max_len,
                      long *time_msec, double *lat_deg, double *lon_deg,
                      double *alt_m, double *heading_deg, double *speed_m_sec);



/*************************************************************************************************
 * the top level server interface, which mostly consists of a structure that defines the
 * context with respective initial values and callbacks
 */

#define DEFAULT_PORT "50037"
#define NO_INTERVAL_PREFERENCE -1

//--- flags (used during client request)

#define DATA_SENDER   0x1
#define DATA_RECEIVER 0x2

//--- reject reasons
#define ACCEPT                      0x0
#define NO_MORE_CONNECTIONS         0x1
#define UNKNOWN_DATA                0x2
#define UNSUPPORTED_INTERVAL        0x4
//... and more to follow


typedef struct {
    //--- static init data
    char* port; // port to serve on
    int flags; // server capabilities
    int interval_msec; // interval at which we send track data to the client if the client has no preference
    
    //--- server state data
    bool stop_local; // set by context to indicate we should terminate

    int (*check_request)(char* host, char* service, int req_flags, char* req_in_type, char* req_out_type, int* data_interval);

    // handle application specific data messages (only the payload, race-adapter takes care of the header)
    int (*write_data)(databuf_t* db, int pos);
    int (*read_data)(databuf_t* db, int pos);

    //--- reporting callbacks
    void (*error)(const char* fmt,...);
    void (*warning)(const char* fmt,...);
    void (*info)(const char* fmt,...);
} local_context_t;

/*
 * run a server that periodically sends data to RACE, checking for messages received from RACE before each cycle
 * 
 * @param ctx pointer to context object holding server config including callbacks
 * @return true if server completed normally
 */
bool race_interval_poll(local_context_t* ctx);

bool race_interval_threaded(local_context_t *context);


#endif /* RACE_INCLUDED */