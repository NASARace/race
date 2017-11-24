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
 * header for a simple open addressing hash map. This is not used by
 * librace itself but applications usually need a map to store flights,
 * hence we provide one for convenience.
 * 
 * Note this implementation does not manage key or data heap objects,
 * which have to be explicitly freed by clients.
 * 
 * NULL is allowed for data but not for keys
 */

#ifndef MAP_INCLUDED
#define MAP_INCLUDED

#include <inttypes.h>
#include <stdbool.h>

typedef struct {
  uint32_t max_entries;
  uint32_t size;
  uint32_t rehash;
} hmap_const_t;

typedef struct {
  const char* key;
  uint32_t hash;
  void* data;
} hmap_entry_t;

typedef struct {
  hmap_const_t consts;  // we copy the values to avoid page faults at runtime - most maps will fit into a page
  int const_idx;
  uint32_t n_entries;
  hmap_entry_t* entries;
} hmap_t;

#define ARRAY_SIZE(a) (sizeof(a)/sizeof(a[0]))


/*
 * FNV-1a hash - fast and good enough for our purposes
 * We keep this here so that applications could pre-hash certain keys
 */
static inline uint32_t hmap_hash (const char* key) {
  uint32_t h = 2166136261ul;
  for (uint8_t* c= (uint8_t*)key; *c; c++) {
    h ^= *c;
    h *= 0x01000193;
  }
  return h;
}

hmap_t* hmap_create (uint32_t init_size);
bool hmap_add_entry (hmap_t* map, const char* key, void* data);
hmap_entry_t* hmap_get_entry (hmap_t* map, const char* key);
bool hmap_remove_entry (hmap_t* map, const char* key);
hmap_entry_t* hmap_next_entry (hmap_t* map, hmap_entry_t* prev_entry);
void hmap_free (hmap_t* map);

void hmap_dump (hmap_t* map); // for debugging purposes

#endif /* MAP_INCLUDED */