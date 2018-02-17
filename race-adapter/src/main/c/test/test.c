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
 * unit tests, mostly for data conversion
 */

#include <stdio.h>
#include <inttypes.h>
#include <time.h>
#include "../race.h"

void test_basic() {
    unsigned char buf[100];
    databuf_t db;

    int pos = race_init_databuf(&db, buf, sizeof(buf));
    pos = race_write_long(&db, pos, 0x1111222233334444);
    pos = race_write_double(&db, pos, 1.2345);
    pos = race_write_string(&db, pos, "blahh", 5);

    race_hex_dump(&db);

    int64_t l;
    double d;
    char s[128];

    pos = race_reset_databuf(&db);
    pos = race_read_long(&db, pos, &l);
    pos = race_read_double(&db, pos, &d);
    pos = race_read_strncpy(&db, pos, s, sizeof(s));

    printf("l=%"PRIx64", d=%f s=\"%s\"\n", l, d, s);
}

void test_time() {
    epoch_millis_t millis = race_epoch_millis();
    printf("current_time_millis = %"PRId64"\n", millis);
    struct tm t;
    race_set_tm_from_epoch_millis(millis, &t);

    char buf[128];
    asctime_r(&t,buf);
    printf("%s\n", buf);
}

int main (int argc, char **argv) {
    test_basic();
    test_time();
    return 0;
}