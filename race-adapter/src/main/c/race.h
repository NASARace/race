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
 * Although this is conceptually partitioned into several layers we keep everything in a single header
 * file to reduce the number of files that have to be accessible from the 3rd party project
 */

#ifndef RACE_INCLUDED
#define RACE_INCLUDED

#define __USE_XOPEN
#include <math.h>

#include <stdbool.h>
#include <stdlib.h>
#include <time.h>
#include <netdb.h>



/*************************************************************************************************
 * network helpers
 */

int race_client_socket (char* hostname, char* service, struct sockaddr** serveraddr, socklen_t* addrlen, const char** err_msg);
int race_server_socket (char* port, const char** err_msg);

struct sockaddr* race_create_sockaddr(socklen_t* socklen);
bool race_set_nonblocking (int fd, const char** err_msg);
bool race_set_blocking (int fd, const char** err_msg);
bool race_set_rcv_timeout (int fd, int millis, const char** err_msg);
int race_check_available (int fd, const char** err_msg);

/*************************************************************************************************
 * time helpers
 */

typedef int64_t epoch_millis_t;

int race_sleep_millis (int millis);
epoch_millis_t race_epoch_millis();
epoch_millis_t race_epoch_millis_from_fsec(double epoch_sec);
bool race_set_tm_from_epoch_millis (epoch_millis_t epoch_millis, struct tm* result);


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

// not all platforms have htonll and ntohll macros 
#ifndef htonll
static inline int64_t htonll(int64_t x) {
    return  (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__) ? (((int64_t)htonl(x & 0xFFFFFFFF) << 32) | htonl(x >> 32)) : x;
}
static inline int64_t ntohll(int64_t x) {
    return (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__) ? (((int64_t)ntohl(x & 0xFFFFFFFF) << 32) | ntohl(x >> 32)) : x;
}
#endif

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
int race_write_long(databuf_t *db, int pos, int64_t v);
int race_write_double(databuf_t *db, int pos, double v);
int race_write_string(databuf_t *db, int pos, char *s, int len);
int race_write_empty_string(databuf_t *db, int pos);

// read values without advancing db->pos
int race_peek_byte(databuf_t *db, int pos, char *v);
int race_peek_short(databuf_t *db, int pos, short *v);
int race_peek_int(databuf_t *db, int pos, int *v);
int race_peek_long(databuf_t *db, int pos, int64_t *v);
int race_peek_double(databuf_t *db, int pos, double *v);

// set values and advance db->pos
int race_read_byte(databuf_t *db, int pos, char *v);
int race_read_short(databuf_t *db, int pos, short *v);
int race_read_int(databuf_t *db, int pos, int *v);
int race_read_long(databuf_t *db, int pos, int64_t *v);
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
#define PAUSE_MSG    6
#define RESUME_MSG   7

#define SERVER_ID 0 // the endpoint ansering requests
#define NO_ID -1
// IDs are dynamically assigned with accept response

#define NO_FIXED_MSG_LEN 0

#define MAX_MSG_LEN      2048 // including header, should be <= MTU to avoid IP fragmentation  
#define MAX_TIME_DIFF    1000 // in msec, if exceeded we adapt event times
#define MAX_SCHEMA_LEN    128 // we only use schema names for now

int race_write_request (databuf_t* db, int flags, char* schema, epoch_millis_t sim_millis, int interval_millis);
int race_is_request (databuf_t* db);
int race_read_request (databuf_t* db, epoch_millis_t* time_sent, int* flags, char* schema, int max_schema_len, 
                       epoch_millis_t* sim_millis, int* interval_millis, 
                       const char** err_msg);

int race_write_accept (databuf_t* db, int flags, epoch_millis_t sim_millis, int interval_millis, int client_id);
int race_is_accept (databuf_t* db);
int race_read_accept (databuf_t* db, int* flags, epoch_millis_t *sim_millis, int* interval_millis, int* client_id, const char** err_msg);

int race_write_reject  (databuf_t* db, int reason);
int race_is_reject (databuf_t* db);
int race_read_reject (databuf_t* db, int* reason, const char** err_msg);

int race_write_stop (databuf_t* db, int sender);
int race_is_stop (databuf_t* db);
int race_read_stop (databuf_t* db, int* sender, epoch_millis_t* time_millis, const char** err_msg);

int race_write_pause (databuf_t* db, int sender_id);
int race_is_pause (databuf_t* db);
int race_read_pause (databuf_t* db, int* sender, epoch_millis_t* time_millis, const char** err_msg);

int race_write_resume (databuf_t* db, int sender_id);
int race_is_resume (databuf_t* db);
int race_read_resume (databuf_t* db, int* sender, epoch_millis_t* time_millis, const char** err_msg);

//--- application messages

//--- data msg API (can go both ways)
int race_begin_write_data (databuf_t* db, int sender_id);
// writing outbound data payload is done in the context layer (we don't know the concrete type here)
int race_end_write_data (databuf_t* db, int pos);

int race_is_data (databuf_t* db);
int race_read_data_header (databuf_t* db, int* sender, epoch_millis_t* time_millis, const char** err_msg);
// reading inbound data payload is done in the context layer (we don't know the concrete type here)


/*************************************************************************************************
 * off-the-shelf data support - tracks and proximities
 */

// simple_track is a virtual type with minimal track state info

#define SIMPLE_TRACK_PROTOCOL "gov.nasa.race.air.SimpleTrackProtocol"
#define EXTENDED_TRACK_PROTOCOL "gov.nasa.race.air.ExtendedTrackProtocol"

// data message types
#define TRACK_MSG 1
#define PROXIMITY_MSG 2
#define DROP_MSG 3

// track flags
#define TRACK_NO_REPORT 0
#define TRACK_NEW    0x1
#define TRACK_CHANGE 0x2
#define TRACK_DROP   0x4     // drop track for unspecified reasons (might be simulator shutdown)
#define TRACK_COMPLETED 0x8  // track was completed as part of simulation (e.g. aircraft landed)
#define TRACK_FROZEN 0x10

// proximity flags
#define PROX_NEW    0x1
#define PROX_CHANGE 0x2
#define PROX_DROP   0x4

// some helpful math
static inline double squared(double v) {
  return v*v;
}

static inline double rad_to_deg (double rad) {
  return rad * (180.0 / M_PI);
}

static inline double deg_to_rad (double deg) {
  return deg / (180.0 / M_PI);
}

int race_write_track_data(databuf_t *db, int pos, char *id, int msg_ordinal, int flags, 
                          epoch_millis_t time_millis, 
                          double lat_deg, double lon_deg, double alt_m, double heading_deg, double speed_m_sec, double vr_m_sec);

int race_read_track_data(databuf_t *db, int pos, char id[], int max_len, int *msg_ordinal, int *flags, 
                         epoch_millis_t *time_millis,
                         double *lat_deg, double *lon_deg, double *alt_m, double *heading_deg, double *speed_m_sec, double *vr_m_sec);

int race_write_proximity_data(databuf_t *db, int pos, char *ref_id, double ref_lat_deg,
                              double ref_lon_deg, double ref_alt_m, double dist_m, int flags,
                              char *prox_id, epoch_millis_t time_millis, double lat_deg, double lon_deg,
                              double alt_m, double heading_deg, double speed_m_sec, double vr_m_sec);

int race_read_proximity_data(databuf_t *db, int pos, char ref_id[], int max_ref_len,
                             double *ref_lat_deg, double *ref_lon_deg, double *ref_alt_m,
                             double *dist_m, int *flags, char prox_id[], int max_prox_len,
                             epoch_millis_t *time_millis, double *lat_deg, double *lon_deg,
                             double *alt_m, double *heading_deg, double *speed_m_sec, double *vr_m_sec);

int race_write_drop_data (databuf_t* db, int pos, char* id, int flags, epoch_millis_t time_millis);

int race_read_drop_data (databuf_t* db, int pos, char id[], int max_len, int* flags, epoch_millis_t* time_millis);


//--- extended track data (more vehicle state)

int race_write_xtrack_data (databuf_t* db, int pos,
                        char* id, int msg_ordinal, int flags, 
                        epoch_millis_t time_millis, double lat_deg, double lon_deg, double alt_m, 
                        double heading_deg, double speed_m_sec, double vr_m_sec,
                        double pitch_deg, double roll_deg, char* track_type);

int race_read_xtrack_data (databuf_t* db, int pos,
                       char id[], int max_id_len, int* msg_ordinal, int* flags,
                       epoch_millis_t* time_millis, double* lat_deg, double* lon_deg, double* alt_m, 
                       double* heading_deg, double* speed_m_sec, double* vr_m_sec,
                       double* pitch_deg, double* roll_deg, char track_type[], int max_type_len);

/*************************************************************************************************
 * the top level server interface, which mostly consists of a structure that defines the
 * context with respective initial values and callbacks
 */

#define DEFAULT_HOST "127.0.0.1"
#define DEFAULT_SERVER_PORT "50036"
#define DEFAULT_CLIENT_PORT "50037"
#define NO_INTERVAL_PREFERENCE -1
#define MAX_POLLED_MSGS 42

#define RECV_TIMEOUT_MILLIS 300 // millis to wait for a server response

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
    char* host; // host/ip to connect to (client mode) or serve on (server mode)
    char* port; // port to connect to (client mode) or serve on (server mode)

    char* schema;

    int flags; // server capabilities
    int interval_millis; // interval at which we send track data to the client if the client has no preference

    long time_diff; // time difference to remote (set after connection is established)
    
    //--- local state data
    bool stop_local; // set by context to indicate we should terminate
    int connect_interval_millis; // interval in which we try to reconnect

    //--- application specific callbacks

    void (*connection_started)(); // optional callback after connection has been established

    int (*write_request)(databuf_t* db, int pos); // client callback to set up request

    // server callback to accept/reject request
    int (*check_request)(char* host, char* service, int req_flags, char* schema, 
                         epoch_millis_t* sim_millis, int* data_interval);

    // handle application specific data messages (only the payload, race-adapter takes care of the header)
    int (*write_data)(databuf_t* db, int pos); // set up data message (both client and server)
    int (*read_data)(databuf_t* db, int pos);  // read data message (both client and server)

    void (*connection_paused)(); // optional callback after connection was paused

    void (*connection_resumed)(); // optional callback after connection was resumed

    void (*connection_terminated)(); // optional callback after connection is terminated

    //--- reporting callbacks
    void (*error)(const char* fmt,...);
    void (*warning)(const char* fmt,...);
    void (*info)(const char* fmt,...);
} local_context_t;


/*
 * high level server/client functions to be called from main
 * these versions periodically send data from the current thread and use a background thread
 * to receive data asynchronously from the remote side
 */
bool race_server(local_context_t *context);
bool race_client(local_context_t *context);


#endif /* RACE_INCLUDED */