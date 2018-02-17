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
 * internal types and function prototypes which are normally not exposed to application code
 */

#ifndef RACE_INTERNAL_INCLUDED
#define RACE_INTERNAL_INCLUDED

#include "race.h"

// the local process
typedef struct {
    int fd;  // socket descriptor
    int interval_millis;  // interval at which we serve
    bool is_non_blocking;
    bool is_stopped;
    databuf_t *db;
    int id;
} local_endpoint_t;


// the external process (RACE actor)
typedef struct {
    socklen_t addrlen;
    struct sockaddr* addr;

    int id; // assigned by server
    long time_request; // when did the client register
    long time_last; // latest client send time (to detect out-of-order)
    bool is_stopped;
    //.. and possibly some more state fields
} remote_endpoint_t;

typedef struct {
    local_context_t *context;
    local_endpoint_t *local;    
    remote_endpoint_t *remote;
} threadargs_t;


#endif /* RACE_INTERNAL_INCLUDED */