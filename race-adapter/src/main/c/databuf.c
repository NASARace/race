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
 * low level read/write buffer operations
 * format follows the java.io.DataStream implementation and uses big-endian
 * (network) byte order
 * Note there are no type tags, only values are stored. It is therefore
 * imperative that writers/readers
 * share the same message definitions
 */

#include <stdio.h>
#include <string.h>

#include "race.h"

databuf_t *race_create_databuf(size_t size) {
    databuf_t *db = (databuf_t *)malloc(sizeof(databuf_t));
    db->buf = malloc(size);
    db->pos = 0;
    db->capacity = size;
    return db;
}

int race_init_databuf(databuf_t *db, unsigned char *buf, int capacity) {
    db->buf = buf;
    db->pos = 0;
    db->capacity = capacity;
    return db->pos;
}

int race_reset_databuf(databuf_t *db) {
    db->pos = 0;
    return db->pos;
}

int race_write_byte(databuf_t *db, int pos, char v) {
    if (race_check_pos(db, pos + 1)) {
        db->buf[db->pos++] = v;
        return db->pos;
    } else {
        return 0;
    }
}

void race_set_short(databuf_t *db, int pos, short v) {
    short n = htons(v);
    unsigned char *b = (unsigned char *)&n;

    unsigned char *buf = db->buf;
    buf[pos++] = b[0];
    buf[pos] = b[1];
}

int race_write_short(databuf_t *db, int pos, short v) {
    if (race_check_pos(db, pos + 2)) {
        short n = htons(v);
        unsigned char *b = (unsigned char *)&n;

        unsigned char *buf = db->buf;
        buf[pos++] = b[0];
        buf[pos++] = b[1];
        db->pos = pos;
        return pos;
    } else {
        return 0;
    }
}

int race_write_int(databuf_t *db, int pos, int v) {
    if (race_check_pos(db, pos + 4)) {
        int n = htonl(v);
        unsigned char *b = (unsigned char *)&n;

        int i = db->pos;
        unsigned char *buf = db->buf;
        buf[pos++] = b[0];
        buf[pos++] = b[1];
        buf[pos++] = b[2];
        buf[pos++] = b[3];
        db->pos = pos;
        return pos;
    } else {
        return 0;
    }
}

int race_write_long(databuf_t *db, int pos, long v) {
    if (race_check_pos(db, pos + 8)) {
        long n = _htonll(v);
        unsigned char *b = (unsigned char *)&n;

        unsigned char *buf = db->buf;
        buf[pos++] = b[0];
        buf[pos++] = b[1];
        buf[pos++] = b[2];
        buf[pos++] = b[3];
        buf[pos++] = b[4];
        buf[pos++] = b[5];
        buf[pos++] = b[6];
        buf[pos++] = b[7];
        db->pos = pos;
        return pos;
    } else {
        return 0;
    }
}

int race_write_double(databuf_t *db, int pos, double v) {
    if (IS_D64 && race_check_pos(db, pos + 8)) { // we assume IEEE-754 here
        long *p = (long *)&v;
        long n = _htonll(*p);
        unsigned char *b = (unsigned char *)&n;

        unsigned char *buf = db->buf;
        buf[pos++] = b[0];
        buf[pos++] = b[1];
        buf[pos++] = b[2];
        buf[pos++] = b[3];
        buf[pos++] = b[4];
        buf[pos++] = b[5];
        buf[pos++] = b[6];
        buf[pos++] = b[7];
        db->pos = pos;
        return pos;
    } else {
        return 0;
    }
}

/**
 * write string to buffer. This is represented as a short representing the number of written bytes,
 * followed by the string chars in UTF8 encoding
 *
 * TODO - this doesn't handle UTF8 yet, only ASCII
 */
int race_write_string(databuf_t *db, int pos, char *s, int len) {
    if (race_check_pos(db, pos + len + 2)) {
        unsigned char *buf = db->buf;
        pos = race_write_short(db, pos, len);
        for (int i = 0; i < len; i++) {
            buf[pos++] = s[i];
        }
        db->pos = pos;
        return pos;
    } else {
        return 0;
    }
}

int race_write_empty_string(databuf_t *db, int pos) { return race_write_short(db, pos, 0); }

int race_peek_byte(databuf_t *db, int pos, char *v) {
    if (race_check_pos(db, pos)) {
        *v = db->buf[pos];
        return pos + 1;
    } else {
        return 0;
    }
}

int race_read_byte(databuf_t *db, int pos, char *v) {
    if (race_check_pos(db, pos)) {
        if (v) {
            *v = db->buf[pos++];
        }
        db->pos = pos + 1;
        return db->pos;
    } else {
        return 0;
    }
}

int race_peek_short(databuf_t *db, int pos, short *v) {
    if (race_check_pos(db, pos + 1)) {
        if (v) {
            short *p = (short *)(db->buf + pos);
            *v = ntohs(*p);
        }
        return pos + 2;
    } else {
        return 0;
    }
}

int race_read_short(databuf_t *db, int pos, short *v) {
    if (race_check_pos(db, pos + 1)) {
        if (v) {
            short *p = (short *)(db->buf + pos);
            *v = ntohs(*p);
        }
        db->pos = pos + 2;
        return db->pos;
    } else {
        return 0;
    }
}

int race_peek_int(databuf_t *db, int pos, int *v) {
    if (race_check_pos(db, pos + 3)) {
        if (v) {
            int *p = (int *)(db->buf + pos);
            *v = ntohl(*p);
        }
        return pos + 4;
    } else {
        return 0;
    }
}

int race_read_int(databuf_t *db, int pos, int *v) {
    if (race_check_pos(db, pos + 3)) {
        if (v) {
            int *p = (int *)(db->buf + pos);
            *v = ntohl(*p);
        }
        db->pos = pos + 4;
        return db->pos;
    } else {
        return 0;
    }
}

int race_peek_long(databuf_t *db, int pos, long *v) {
    if (race_check_pos(db, pos + 7)) {
        if (v) {
            long *p = (long *)(db->buf + pos);
            *v = _ntohll(*p);
        }
        return pos + 8;
    } else {
        return 0;
    }
}

int race_read_long(databuf_t *db, int pos, long *v) {
    if (race_check_pos(db, pos + 7)) {
        if (v) {
            long *p = (long *)(db->buf + pos);
            *v = _ntohll(*p);
        }
        db->pos = pos + 8;
        return db->pos;
    } else {
        return 0;
    }
}

int race_peek_double(databuf_t *db, int pos, double *v) {
    if (race_check_pos(db, pos + 7)) {
        if (v) {
            long *p = (long *)(db->buf + pos);
            long l = _ntohll(*p);
            *v = *(double *)&l;
        }
        return pos + 8;
    } else {
        return 0;
    }
}

int race_read_double(databuf_t *db, int pos, double *v) {
    if (race_check_pos(db, pos + 7)) {
        if (v) {
            long *p = (long *)(db->buf + pos);
            long l = _ntohll(*p);
            *v = *(double *)&l;
        }
        db->pos = pos + 8;
        return db->pos;
    } else {
        return 0;
    }
}

/*
 * read string into freshly allocated memory, appending 0. It is the callers
 * responsibility to free this memory
 *
 * TODO - this does not handle UTF8 yet, only ASCII
 */
int race_read_strdup(databuf_t *db, int pos, char **s) {
    if (race_check_pos(db, pos + 2)) {
        short len = 0;
        pos = race_read_short(db, pos, &len);

        if (len > 0) {
            if (race_check_pos(db, pos + len)) {
                if (s) {
                    *s = strndup(((char *)db->buf + pos), len);
                }
                db->pos = pos + len;
                return db->pos;
            } else { // truncated string - not enough chars left
                db->pos -= 2;
                return 0;
            }
        } else if (len == 0) {
            if (s) {
                *s = malloc(1);
                *s[0] = 0;
            }
            db->pos = pos;
            return db->pos;
        } else { // negative string length
            return 0;
        }
    } else { // not enough bytes left for string length
        return 0;
    }
}

/*
 * read string into existing buffer, appending 0
 *
 * TODO - this does not handle UTF8 yet, only ASCII
 */
int race_read_strncpy(databuf_t *db, int pos, char *dest, int max_len) {
    if (race_check_pos(db, pos + 2)) {
        short len = 0;
        pos = race_read_short(db, pos, &len);

        if (len > 0) {
            if (race_check_pos(db, pos + len)) {
                int n = len > max_len - 1 ? max_len - 1 : len;
                if (dest) {
                    memcpy(dest, ((char *)db->buf + pos), n);
                    dest[n] = 0;
                }
                db->pos = pos + len;
                return db->pos;
            } else { // truncated string - not enough chars left
                db->pos -= 2;
                return 0;
            }
        } else if (len == 0) {
            if (dest) {
                dest[0] = 0;
            }
            db->pos = pos;
            return db->pos;
        } else { // negative string length
            return 0;
        }
    } else { // not enough bytes left for string length
        return 0;
    }
}

void race_hex_dump(databuf_t *db) {
    unsigned char *b = db->buf;
    int n = db->pos;
    int pos = 0;

    while (pos < n) {
        for (int i = 0; pos < n && i < 16; i++) {
            printf("%02x ", b[pos++]);
        }
        printf("\n");
    }
}

#ifdef TEST

int main(int argc, char **argv) {
    unsigned char buf[100];
    databuf_t db;

    int pos = race_init_databuf(&db, buf, sizeof(buf));
    pos = race_write_long(&db, pos, 42);
    pos = race_write_double(&db, pos, 1.2345);
    pos = race_write_string(&db, pos, "blahh", 5);

    hex_dump(&db);

    long l;
    double d;
    char s[128];

    pos = race_reset_databuf(&db);
    pos = race_read_long(&db, pos, &l);
    pos = race_read_double(&db, pos, &d);
    pos = race_read_string(&db, pos, s, sizeof(s));

    printf("l=%ld, d=%f s=\"%s\"\n", l, d, s);

    return 1;
}

#endif /* TEST */