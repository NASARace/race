#include <stdio.h>
#include "race.h"

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

    printf("l=%llx, d=%f s=\"%s\"\n", l, d, s);
}

void test_time() {
    epoch_msec_t t = race_epoch_msec();
    printf("current_time_millis = %lld\n", t);
}

int main (int argc, char **argv) {
    test_basic();
    test_time();
    return 0;
}