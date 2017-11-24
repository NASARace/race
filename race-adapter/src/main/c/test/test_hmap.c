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
 * unit test for hmap
 */

#include <stdlib.h>
#include <stdio.h>
#include <assert.h>
#include <time.h>

#include "../hmap.h"

struct test_entry {
  char* key;
  char* data;
};

void basicTestCycle () {
  struct test_entry data[] = {
    {"FZ1", "fz1"},
    {"XU42", "xu42"},
    {"A24", "a24"},
    {"ZZ333", "zz333"},
    {"FOO", "foo"},
    {"YPP453", "ypp453"}
  };

  puts("\n-------------- basicTestCycle");

  puts("-- create map");
  hmap_t* map = hmap_create(32);

  puts("-- add entries");
  for (int i=0; i<ARRAY_SIZE(data); i++) {
    hmap_add_entry(map,data[i].key, data[i].data);
  }
  assert( map->n_entries == ARRAY_SIZE(data));

  puts("-- enumerate entries");
  int i = 0;
  for (hmap_entry_t* e = hmap_next_entry(map, NULL); e; e = hmap_next_entry(map, e)){
    printf("%d: (%s, %s)\n", ++i, e->key, (char*)e->data);
  }

  puts("-- lookup existing entries");
  for (int i=0; i<ARRAY_SIZE(data); i++) {  
    hmap_entry_t* e = hmap_get_entry(map, data[i].key);
    assert(e);
    printf("%s -> %s\n", e->key, (char*)e->data);
  }

  puts("-- lookup missing entries");
  assert( hmap_get_entry(map, "nope") == NULL);
  
  puts("-- delete entries");
  for (int i=0; i<ARRAY_SIZE(data); i++) {
    hmap_remove_entry(map,data[i].key);
  }
  assert( map->n_entries == 0);
  
  puts("-- lookup deleted entries");
  for (int i=0; i<ARRAY_SIZE(data); i++) {  
    hmap_entry_t* e = hmap_get_entry(map, data[i].key);
    assert(e == NULL);
  }

  puts("-- free map");
  hmap_free(map);
}

void growMap () {
  puts("\n-------------- growMap");
  const int N = 64;

  puts("-- hmap_create(8)");
  hmap_t* map = hmap_create(8);

  printf("-- now adding %d entries\n", N);
  for (int i=0; i<N; i++) {
    char *key = malloc(8);
    snprintf(key, 8, "A%d", i);
    hmap_add_entry(map, key, (void*)(long)i);
  }

  printf("-- checking map size: %d\n", map->n_entries);
  assert(map->n_entries == N);

  puts("-- verifying entries");
  for (int i=0; i<N; i++) {
    char kb[8];
    snprintf(kb, 8, "A%d", i);
    hmap_entry_t* e = hmap_get_entry(map,kb);
    assert(e);
    assert((long)e->data == i);
  }

  hmap_dump(map);
}

void randomOp () {
  const int M = 300;  // number of keys/entries we generate
  const long N = 1000000; // rounds
  const char* clear_line = "\e[1K\e[0G";
  int n_rehash = 0;

  hmap_entry_t* e = NULL;
  //srand(time(NULL));
  srand(0);

  puts("\n-------------- random op");
  hmap_t* map = hmap_create(8);
  
  char* keys[M];
  for (int i=0; i<M; i++){
    char *key = malloc(8);
    snprintf(key, 8, "FZ%d", i);
    keys[i] = key;
  }

  printf("-- performing %ld random ops..\n", N);
  for (long i=0; i<N; i++){
    if (i % 1000 == 0) {
      printf("%sround %ld", clear_line, i);
    }
    int r = rand();
    int idx = r % M;

    char* key = keys[idx]; 
    void* a = map->entries;
    uint32_t n_rem = map->n_removed;

    if (i < 1000 || (i & 1)) { // fill up to 1000 entries, the alternate between add/remove
      hmap_add_entry(map, key, (void*)i);
      e = hmap_get_entry(map,key);
      assert(e != NULL && (long)e->data == i);
      //assert(check_duplicates(map));

    } else { // remove
      hmap_remove_entry(map, key);
      e = hmap_get_entry(map,key);
      assert(e == NULL);
    }

    if (map->entries != a) {
      //printf("  rehash %d in round %ld (n_rem=%d) to size %d\n", ++n_rehash, i, n_rem, map->consts.size);
    }
  }
  printf("%sdone.\n", clear_line);
}

int main (int argc, char** argv) {
  basicTestCycle();
  growMap();
  randomOp();
  return 0;
}
