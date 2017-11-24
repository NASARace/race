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

  puts("-------------- basicTestCycle");

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
  puts("-------------- growMap");
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

int main (int argc, char** argv) {
  basicTestCycle();
  growMap();
  return 0;
}
