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
 * RACE adapter time utility functions
 */

#include <time.h>
#include <math.h>
#include "race.h"

/*
 * send thread to sleep for specified number of milliseconds
 */
int race_sleep_millis (int millis) {
    int sec = millis / 1000;
    int nsec = (millis - (sec * 1000)) * 1e6;

    struct timespec ts;
    ts.tv_sec = sec;
    ts.tv_nsec = nsec;

    return nanosleep(&ts,NULL);
}

/*
 * get wallclock epoch in milliseconds
 */
epoch_millis_t race_epoch_millis () {
    epoch_millis_t ms;
    struct timespec spec;
  
    clock_gettime( CLOCK_REALTIME, &spec);
    ms = spec.tv_sec * 1000LL + lround(spec.tv_nsec / 1e6);
    return ms;
}

/*
 * get epoch in milliseconds from fractional epoch
 */
epoch_millis_t race_epoch_millis_from_fsec(double sec) {
    return (epoch_millis_t)((sec + 0.0005) * 1000);
}

bool race_set_tm_from_epoch_millis (epoch_millis_t epoch_millis, struct tm* result) {
    // TODO - handle exceeding epoch_millis_t values
    time_t seconds = (time_t)(epoch_millis/1000);
    return localtime_r(&seconds,result) != NULL;
}