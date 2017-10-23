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

#include <time.h>
#include <math.h>

int race_sleep_msec (int millis) {
    int sec = millis / 1000;
    int nsec = (millis - (sec * 1000)) * 1e6;

    struct timespec ts;
    ts.tv_sec = sec;
    ts.tv_nsec = nsec;

    return nanosleep(&ts,NULL);
}

long race_epoch_msec () {
    long ms;
    struct timespec spec;
  
    clock_gettime( CLOCK_REALTIME, &spec);
    ms = spec.tv_sec * 1000 + round(spec.tv_nsec / 1.0e6);
    return ms;
}