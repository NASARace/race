 // CommonJS import
 //const assert = require("assert");
 //const { LinkedList, SkipList } = require("../../main/resources/gov/nasa/race/ui/ui_data");

 // ES6 import
 import { LinkedList, SkipList, CircularBuffer } from '../../main/resources/gov/nasa/race/ui/ui_data.js';
 import assert from 'assert/strict';


 function testSkipList() {
     console.log("\n--- testing SkipList");

     let list = new SkipList((a, b) => a < b, (a, b) => a == b);

     [1, 10, 5, 3, 2, 11].forEach(v => list.insert(v));

     console.log(list.toString());


     let testIncludes = function(list, v, expect) {
         let res = list.includes(v);
         console.log("includes " + v + ": " + res + ", expected: " + expect);
         assert(res == expect);
     }

     console.log("size = " + list.size);
     testIncludes(list, 7, false);
     testIncludes(list, 5, true);

     let testRemove = function(list, v, expect) {
         let res = list.remove(v);
         console.log("remove " + v + ": " + res + ", expected: " + expect);
         assert(res == expect);
     }

     testRemove(list, 5, true);
     testRemove(list, 77, false);
     testRemove(list, 1, true);
     testRemove(list, 11, true);
     console.log(list.toString());

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