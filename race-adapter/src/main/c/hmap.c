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
 * simple open addressing/delayed delete hash map for string keys.
 * 
 * The implementation is a variation of https://github.com/anholt/hash_table.git
 * that tries to minimize allocation, assuming that we usually have data sets
 * with <500 entries. We also don't grow the table upon rehash if the number of
 * removed entries exceeds a size-specific limit.
 * 
 * TODO - If entries are highly dynamic it might be better to forego the double
 * hash in favor or recycling removed entries if their successor slot is free
 * 
 * Note that we do not duplicate keys and don't manage data. If the map is a 
 * heap root clients have to explicitly free keys and data.
 */

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include "hmap.h"

#define DELETED_HASH -1

// max_entries,size are p,p-2 prime (Knuth)
// max_rem is arbitrary and assumes a mostly constant add/remove ratio 

static hmap_const_t map_consts[] = {
//     max_ent   max_rem       size     rehash
    {        8,        2,        13,        11 },
    {       16,        3,        19,        17 },
    {       32,        6,        43,        41 },
    {       64,        8,        73,        71 },
    {      128,       10,       151,       149 },
    {      256,       20,       283,       281 },
    {      512,       40,       571,       569 },
    {     1024,       80,      1153,      1151 },
    {     2048,      150,      2269,      2267 },
    {     4096,      300,      4519,      4517 },
    {     8192,      600,      9013,      9011 },
    {    16384,     1000,     18043,     18041 },
    {    32768,     2000,     36109,     36107 },
    {    65536,     3000,     72091,     72089 },
    {   131072,     5000,    144409,    144407 },
    {   262144,     8000,    288361,    288359 },
    {   524288,    10000,    576883,    576881 },
    {  1048576,    10000,   1153459,   1153457 },
    {  2097152,    10000,   2307163,   2307161 },
    {  4194304,    10000,   4613893,   4613891 },
    {  8388608,    10000,   9227641,   9227639 },
    { 16777216,    10000,  18455029,  18455027 }
};

int get_const_idx (uint32_t size) {
  for (int i=0; i<ARRAY_SIZE(map_consts); i++) {
    if (map_consts[i].size >= size) return i;
  }
  return -1;
}

static inline bool is_free_entry (hmap_entry_t* e) {
  return (e->key == NULL) && (e->hash == 0);
}

static inline bool is_deleted_entry(hmap_entry_t* e) {
  return e->hash == DELETED_HASH;
}

static inline bool is_matching_entry (hmap_entry_t* e, uint32_t hash, const char* key) {
  return e->hash == hash && e->key && (strcmp(e->key, key) == 0);
}

static bool rehash (hmap_t* map, bool grow) {
  int const_idx = grow ? map->const_idx+1 : map->const_idx;
  if (const_idx < ARRAY_SIZE(map_consts)) {
    uint32_t new_size = map_consts[const_idx].size;
    uint32_t new_rehash = map_consts[const_idx].rehash;
    hmap_entry_t* new_entries = calloc(sizeof(hmap_entry_t), new_size);
    int n_rehashed = 0;
    int n_entries = map->n_entries;

    for (hmap_entry_t* e = map->entries; n_rehashed < n_entries; e++) {
      if (e->key){
        uint32_t h = e->hash;
        int new_idx = h % new_size;
        hmap_entry_t* new_e = &new_entries[new_idx];

        while (!is_free_entry(new_e)){
          uint32_t h2 = 1 + h % new_rehash;
          new_idx = (new_idx + h2) % new_size;
          new_e = &new_entries[new_idx];
        }

        new_e->key = e->key;
        new_e->hash = e->hash;
        new_e->data = e->data;
        n_rehashed++;
      }
    }

    free(map->entries);
    map->entries = new_entries;
    if (grow){
      map->const_idx = const_idx;
      memcpy(&map->consts, &map_consts[const_idx], sizeof(hmap_const_t));
    }
    map->n_removed = 0;
    // n_entries has not changed

    return true;
  } else {
    return false;
  }
}

static inline bool check_rehash (hmap_t* map) {
  if (map->n_entries + map->n_removed >= map->consts.max_entries) {
    return rehash(map, map->n_removed <= map->consts.max_removed);
  } else {
    return true;
  }
}

static inline int next_index (hmap_t* map, int idx, uint32_t h) {
  uint32_t h2 = 1 + h % map->consts.rehash;
  return (idx + h2) % map->consts.size; 
}

static inline bool is_next_entry_free (hmap_t* map, int idx, uint32_t h) {
  return is_free_entry(&map->entries[next_index(map,idx,h)]);
}

static inline void reloc_entry (hmap_t* map, int idx, uint32_t h, hmap_entry_t* e_from, hmap_entry_t* e_to ) {
  e_to->key = e_from->key;
  e_to->hash = e_from->hash;
  e_to->data = e_from->data;

  e_from->key = NULL;
  e_from->hash = DELETED_HASH;
  e_from->data = NULL;
}

//--- the public functions

hmap_t* hmap_create (uint32_t init_size) {
  int const_idx = get_const_idx(init_size);
  if (const_idx >= 0) {
    hmap_t* map = malloc(sizeof(hmap_t));
    map->n_entries = 0;
    map->n_removed = 0;
    map->const_idx = const_idx;
    memcpy(&map->consts, &map_consts[const_idx], sizeof(hmap_const_t));
    map->entries = calloc(map_consts[const_idx].size, sizeof(hmap_entry_t));
    return map;
  } else {
    return NULL;
  }
}

bool hmap_add_entry (hmap_t* map, const char* key, void* data) {
  if (!check_rehash(map)) return false; // this is conservative since we might not need more space for replaced entries
  uint32_t h = hmap_hash(key);

  for (int idx = h % map->consts.size; true; idx = next_index(map,idx,h)) {
    hmap_entry_t* e = &map->entries[idx];
    if (is_free_entry(e)) {  // new entry
      e->key = key;
      e->hash = h;
      e->data = data;
      map->n_entries++;
      return true;

    } else if (is_matching_entry(e, h, key)) { // replaced entry
      e->key = key; // the address might have changed
      e->data = data;
      return true;
    }
  }
}

hmap_entry_t* hmap_get_entry (hmap_t* map, const char* key) {
  uint32_t h = hmap_hash(key);
  hmap_entry_t* e_reloc = NULL;

  for (int idx = h % map->consts.size; true; idx = next_index(map,idx,h)) {
    hmap_entry_t* e = &map->entries[idx];
    if (is_free_entry(e)) {
      return NULL;

    } else if (is_matching_entry(e,h,key)) {
      if (e_reloc != NULL) {
        reloc_entry(map, idx, h, e, e_reloc);
        return e_reloc;
      } else {
        return e;
      }

    } else if (e_reloc == NULL && is_deleted_entry(e)) { // move it up to speed subsequent lookup
      e_reloc = e;
    }
  }
}

bool hmap_remove_entry (hmap_t* map, const char* key) {
  uint32_t h = hmap_hash(key);

  for (int idx = h % map->consts.size; true; idx = next_index(map,idx,h)) {
    hmap_entry_t* e = &map->entries[idx];
    if (is_free_entry(e)) {
      return false;
    } else if (is_matching_entry(e,h,key)) {
      e->key = NULL;
      e->hash = DELETED_HASH; // we can't mark as free because it might break double hash lookup
      e->data = NULL;

      map->n_entries--;
      map->n_removed++;
      return true;
    }
  }
}

hmap_entry_t* hmap_next_entry (hmap_t* map, hmap_entry_t* prev_entry) {
  hmap_entry_t* e_last = map->entries + map->consts.size;
  hmap_entry_t* e_first = (prev_entry == NULL) ? map->entries : prev_entry+1;

  for (hmap_entry_t* e = e_first; e != e_last; e++) {
    if (e->key) { // skip over free and deleted entries
      return e;
    }
  }
  return NULL;
}

void hmap_free (hmap_t* map) {
  free(map->entries);
  free(map);
}

void hmap_dump (hmap_t* map) {
  for (int i=0; i<map->consts.size; i++){
    hmap_entry_t* e = &map->entries[i];
    if (e->key == NULL){
      printf("%5d: -\n", i);
    } else {
      printf("%5d: (%-8s, %8x, %p)\n", i, e->key, e->hash, e->data);
    }
  }
}

bool check_duplicates (hmap_t* map) {
  for (int i=0; i<map->consts.size; i++){
    hmap_entry_t* e = &map->entries[i];
    if (e->key != NULL){
      for (int j = i+1; j <map->consts.size; j++){
        hmap_entry_t* e1 = &map->entries[j];
        if (e1->key != NULL){
          if (strcmp(e->key,e1->key) == 0){
            fprintf(stderr, "found duplicate keys:\n");
            fprintf(stderr, "  %d: { key=%s, hash=%8x, data=%p }\n", i, e->key, e->hash, e->data);
            fprintf(stderr, "  %d: { key=%s, hash=%8x, data=%p }\n", j, e1->key, e1->hash, e1->data);
            return false;
          }
        }
      }
    }
  }
  return true;
}