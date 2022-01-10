 // CommonJS import
 //const assert = require("assert");
 //const { LinkedList, SkipList } = require("../../main/resources/gov/nasa/race/ui/ui_data");

 // ES6 import
 import { LinkedList, SkipList, CircularBuffer } from '../../main/resources/gov/nasa/race/ui/ui_data.js';
 import assert from 'assert/strict';


 function testSkipList() {
     console.log("\n--- testing SkipList");

     let list = new SkipList(5, (a, b) => a < b, (a, b) => a == b);

     console.log("-- test insert");

     function testInsert(list, v) {
         let idx = list.insert(v);

         console.log("\ninsert " + v + " -> " + idx + ": " + list.toString());
         list.dump();
     }

     //[1, 10, 5, 3, 2, 11].forEach(v => testInsert(list, v));
     [1, 7, 2, 3, 11, 5, 0, 42, 8, 12].forEach(v => testInsert(list, v));

     //console.log(list.toString());

     console.log("\n-- test at(idx)");

     function testAt(list, i, expect) {
         let res = list.at(i);
         console.log("at " + i + " -> " + res);
         if (expect) assert(res == expect)
     }

     testAt(list, 0, 0);
     testAt(list, 1, 1);
     testAt(list, 2, 2);
     testAt(list, 3, 3);
     testAt(list, 4, 5);
     testAt(list, 5, 7);
     testAt(list, 6, 8);
     testAt(list, 7, 11);
     testAt(list, 8, 12);
     testAt(list, 9, 42);

     console.log("\n-- test includes(v)");

     function testIncludes(list, v, expect) {
         let res = list.includes(v);
         console.log("includes " + v + ": " + res + ", expected: " + expect);
         assert(res == expect);
     }

     testIncludes(list, 6, false);
     testIncludes(list, 5, true);

     console.log("\n-- test remove(v)");

     function testRemove(list, v, expect) {
         let res = list.remove(v);
         console.log("\nremove " + v + ": " + res + ", expected: " + expect + ": " + list.toString());
         list.dump();
         assert(res == expect);
     }

     testRemove(list, 5, true);
     assert(list.at(5) == 8);
     assert(list.at(7) == 12);

     testRemove(list, 0, true);
     assert(list.at(0) == 1);
     assert(list.at(7) == 42);

     testRemove(list, 42, true);
     assert(list.at(0) == 1);
     assert(list.at(6) == 12);
     assert(list.at(7) == null);

     testRemove(list, 42, false);

     console.log("Ok.");
 }

 function testCircularBuffer() {
     console.log("\n--- testing CircularBuffer");

     let buffer = new CircularBuffer(4);

     let testPush = function(buffer, v, expectedSize) {
         buffer.push(v);
         console.log("push " + v + ": size=" + buffer.size + ", data=" + buffer.toString());
         assert(buffer.size == expectedSize);
     }

     let testDropFirst = function(buffer, n, expectedSize) {
         buffer.dropFirst(n);
         console.log("dropFirst " + n + ": size=" + buffer.size + ", data=" + buffer.toString());
         assert(buffer.size == expectedSize);
     }

     let testDropLast = function(buffer, n, expectedSize) {
         buffer.dropLast(n);
         console.log("dropLast " + n + ": size=" + buffer.size + ", data=" + buffer.toString());
         assert(buffer.size == expectedSize);
     }


     testPush(buffer, 1, 1);
     testPush(buffer, 2, 2);
     testPush(buffer, 3, 3);
     testPush(buffer, 4, 4);
     testPush(buffer, 5, 4);

     assert(buffer.at(0) == 2);
     assert(buffer.at(1) == 3);
     assert(buffer.at(2) == 4);
     assert(buffer.at(3) == 5);
     assert(buffer.at(4) == undefined);

     assert(buffer.reverseAt(0) == 5);
     assert(buffer.reverseAt(1) == 4);
     assert(buffer.reverseAt(2) == 3);
     assert(buffer.reverseAt(3) == 2);
     assert(buffer.reverseAt(4) == undefined);

     var i = 0;
     buffer.forEach(v => {
         console.log("[" + i + "]: " + v);
         i++;
     });


     testDropFirst(buffer, 2, 2);
     testDropLast(buffer, 1, 1);
     assert(buffer.at(0) == 4);
     testDropLast(buffer, 2, 0);

     testPush(buffer, 42, 1);
     assert(buffer.first() == 42);
     assert(buffer.last() == 42);

     console.log("Ok.");
 }

 testSkipList();
 testCircularBuffer();