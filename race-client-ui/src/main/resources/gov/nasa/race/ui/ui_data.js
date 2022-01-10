class LinkedListNode {
    constructor(data, next = null) {
        this.data = data;
        this.next = next;
    }
}

class LinkedList {
    constructor() {
        this.head = null;
        this.size = 0;
    }

    push(data) {
        this.head = new LinkedListNode(data, this.head);
        this.size++;
    }

    insert(data, cond) {
        this.size++;
        if (this.head) {
            let prevNode = null;
            let node = this.head;
            while (node) {
                if (cond(data, node.data)) {
                    let newNode = new LinkedListNode(data, node);
                    if (prevNode) {
                        prevNode.next = newNode;
                    } else {
                        this.head = newNode;
                    }
                    return;
                }
                prevNode = node;
                node = node.next;
            }
            node.next = new LinkedListNode(data, null);

        } else {
            this.head = new LinkedListNode(data, null);
        }
    }

    //... and more

    *
    [Symbol.iterator]() { // define the default iterator for this class
        let node = this.head; // get first node
        while (node) { // while we have not reached the end of the list
            yield node.data; // ... yield the current node's data
            node = node.next; // and move to the next node
        }
    }

    forEach(f) {
        for (let data of this) f(data);
    }
}

class SkipListNode {
    constructor(data, next) {
        this.data = data;
        this.next = next;

        this.width = new Array(next.length);
        this.width.fill(0);
    }
}

//const MaxSkipListDepth = 16;
const MaxSkipListDepth = 5;

class SkipList {
    constructor(depth, isBefore, isSame) {
        this.depth = depth;
        this.isBefore = isBefore;
        this.isSame = isSame;

        this.head = new SkipListNode(null, new Array(depth));
        this.size = 0;
        this.maxLevel = 0; // index - there always is at least one level
    }

    includes(data) {
        let n = this.head;
        for (let lvl = this.maxLevel; lvl >= 0; lvl--) {
            while (n.next[lvl] && this.isBefore(n.next[lvl].data, data)) n = n.next[lvl];
        }
        n = n.next[0];
        return (n && this.isSame(data, n.data))
    }

    randomLevel() {
        var lvl = 0;
        while (lvl < this.depth && Math.random() < 0.5) lvl++;
        return lvl;
    }

    insert(data) {
        let idx = 0; // eventually holds level 0 index of new element
        let nodeLevel = 0;
        let update = new Array(this.maxLevel + 1);
        let updateIdx = new Array(update.length); // index of update node
        let n = this.head;

        for (let lvl = this.maxLevel; lvl >= 0; lvl--) {
            while (n.next[lvl] && this.isBefore(n.next[lvl].data, data)) {
                idx += n.width[lvl];
                n = n.next[lvl];
            }
            update[lvl] = n;
            updateIdx[lvl] = idx - 1;
        }

        n = n.next[0];
        if (n && this.isSame(n.data, data)) { // update (keep it similar to Map)
            n.data = data;

        } else { // insert or append
            nodeLevel = this.randomLevel();
            n = new SkipListNode(data, new Array(nodeLevel + 1));
            let oldMaxLevel = this.maxLevel;

            if (nodeLevel > oldMaxLevel) { // first node of this level, link new skip lanes from head
                for (let lvl = oldMaxLevel + 1; lvl <= nodeLevel; lvl++) {
                    this.head.next[lvl] = n;
                    this.head.width[lvl] = idx + 1;
                }
                this.maxLevel = nodeLevel;
            }

            //--- base lane update
            n.next[0] = update[0].next[0];
            update[0].next[0] = n;
            update[0].width[0] = 1;
            if (n.next[0]) n.width[0] = 1;

            //--- pre-existing skip lane updates
            for (let lvl = 1; lvl <= oldMaxLevel; lvl++) {
                let u = update[lvl];
                let uw = u.width[lvl];

                if (lvl > nodeLevel) { // u is above our level chain
                    if (uw > 0) u.width[lvl]++;

                } else { // insert into level chain
                    n.next[lvl] = u.next[lvl];
                    u.next[lvl] = n;

                    let uIdx = updateIdx[lvl];
                    u.width[lvl] = (idx - uIdx);
                    if (uw > 0) n.width[lvl] = uw - (idx - uIdx) + 1;
                }
            }

            this.size++;
        }

        return idx;
    }

    remove(data) {
        let update = [];
        let n = this.head;

        for (let lvl = this.maxLevel; lvl >= 0; lvl--) {
            while (n.next[lvl] && this.isBefore(n.next[lvl].data, data)) n = n.next[lvl];
            update[lvl] = n;
        }

        n = n.next[0];
        if (n && this.isSame(n.data, data)) {
            //--- update skip lane widths
            for (let lvl = 1; lvl <= this.maxLevel; lvl++) {
                let u = update[lvl];
                let uw = u.width[lvl];
                let nodeLevel = n.next.length - 1;

                if (lvl > nodeLevel) { // above our level chain
                    if (uw > 0) u.width[lvl]--;
                } else {
                    if (n.next[lvl]) u.width[lvl] += n.width[lvl] - 1;
                    else u.width[lvl] = 0;
                }
            }
            if (n.next[0] == null) update[0].width[0] = 0;

            for (let lvl = 0; lvl <= this.maxLevel && update[lvl].next[lvl] === n; lvl++) {
                update[lvl].next[lvl] = n.next[lvl];
            }
            for (let lvl = this.maxLevel; lvl >= 0 && this.head.next[lvl] == null; lvl--) this.maxLevel--;

            this.size--;
            return true;

        } else { // nothing to remove
            return false;
        }
    }

    at(idx) {
        let n = this.head;
        idx++;
        for (let lvl = this.maxLevel; lvl >= 0; lvl--) {
            while (n.width[lvl] && idx >= n.width[lvl]) {
                idx -= n.width[lvl];
                if (idx == 0) {
                    let nn = n.next[lvl];
                    return nn ? nn.data : null;
                }
                n = n.next[lvl];
            }
        }
        return null;
    }

    *
    [Symbol.iterator]() {
        if (this.size > 0) {
            let n = this.head.next[0];
            while (n) {
                yield n.data;
                n = n.next[0];
            }
        }
    }

    forEach(f) {
        for (let data of this) f(data);
    }

    clear() {
        for (let lvl = this.maxLevel; lvl >= 0; lvl--) this.head.next[lvl] = null;
        this.size = 0;
        this.maxLevel = 0;
    }

    //--- debug funcs

    toString() {
        let s = "[";
        let i = 0;
        for (let data of this) {
            if (i > 0) s += ',';
            s += JSON.stringify(data);
            i++;
        }
        s += ']';
        return s;
    }

    checkOrder(labelFunc) {
        let prev = null;
        let n = this.head.next[0];
        for (let i = 0; i < this.size; i++) {
            if (prev) {
                if (!this.isBefore(prev.data, n.data)) {
                    console.log("!!! wrong order at index: " + i);
                    if (labelFunc) this.dumpOrder(labelFunc, i + 1);

                    return false;
                }
            }
            prev = n;
            n = n.next[0];
        }
        return true;
    }

    dumpOrder(labelFunc, i = this.size) {
        let n = this.head.next[0];
        for (let j = 0; j < i; j++) {
            console.log("  " + j + ": " + labelFunc(n.data));
            n = n.next[0];
        }
    }

    dump(colWidth = 3, labelFunc = undefined) {
        function toString(x) {
            if (x != null) {
                let s = labelFunc ? labelFunc(x) : x.toString();
                if (s.length < colWidth) s = " ".repeat(colWidth - s.length) + s;
                return s;
            } else return "  -";
        }

        function nid(n) {
            if (n) return "(" + toString(n.data) + ")";
            else return "     ";
        }

        function printNode(prefix, n) {
            let line = prefix + ": (" + toString(n.data) + ") : ";
            for (let i = 0; i < n.next.length; i++) line += " ".repeat(colWidth) + nid(n.next[i]) + "+" + (n.width[i]);
            console.log(line);
        }

        console.log("-------------------------------------------------------------------- size " + this.size);
        printNode(" hd", this.head);
        let n = this.head.next[0];
        for (let i = 0; i < this.size; i++) {
            printNode(toString(i), n);
            n = n.next[0];
        }
    }
}

class CircularBuffer {
    constructor(maxSize) {
        this.maxSize = maxSize;
        this.size = 0;
        this.i0 = -1;
        this.i1 = -1;
        this.buffer = [];
    }

    push(v) {
        this.i1++;
        this.i1 %= this.maxSize;
        this.buffer[this.i1] = v;

        if (this.size < this.maxSize) {
            if (this.size == 0) this.i0 = 0;
            this.size++;
        } else {
            this.i0++;
            this.i0 %= this.maxSize;
        }
    }

    dropLast(n) {
        if (n > 0) {
            if (n >= this.size) this.clear();
            else {
                this.size -= n;
                this.i1 -= n;
                if (this.i1 < 0) this.i1 += this.maxSize;
            }
        }
    }

    dropFirst(n) {
        if (n > 0) {
            if (n >= this.size) this.clear();
            else {
                this.size -= n;
                this.i0 += n;
                this.i0 %= this.maxSize;
            }
        }
    }

    at(i) {
        if (i >= 0 && i < this.size) {
            let idx = (this.i0 + i) % this.maxSize;
            return this.buffer[idx];
        } else return undefined;
    }


    reverseAt (i) {
        if (i >= 0 && i < this.size) {
            let idx = (this.i1 - i);
            if (idx < 0) idx = this.maxSize + idx;
            return this.buffer[idx];
        } else return undefined;    }

    *
    [Symbol.iterator]() {
        if (this.size > 0) {
            let i = this.i0;
            while (true) {
                yield this.buffer[i];
                if (i == this.i1) break;
                i++;
                i %= this.maxSize;
            }
        }
    }

    forEach(f) {
        for (let v of this) f(v);
    }

    first() {
        if (this.size > 0) return this.buffer[this.i0];
        else return undefined;
    }

    last() {
        if (this.size > 0) return this.buffer[this.i1];
        else return undefined;
    }

    clear() {
        this.size = 0;
        this.i0 = -1;
        this.i1 = -1;
        this.buffer = [];
    }

    toString() {
        let s = "[";
        let i = 0;
        for (let data of this) {
            if (i > 0) s += ',';
            s += JSON.stringify(data);
            i++;
        }
        s += ']';
        return s;
    }
}

export { LinkedList, SkipList, CircularBuffer };

//--- simple seeded PRNG to support reproducible values for testing (lifted from http://pracrand.sourceforge.net/)

function _sfc32(a, b, c, d) {
    return function() {
        a >>>= 0;
        b >>>= 0;
        c >>>= 0;
        d >>>= 0;
        let t = (a + b) | 0;
        a = b ^ b >>> 9;
        b = c + (c << 3) | 0;
        c = (c << 21 | c >>> 11);
        d = d + 1 | 0;
        t = t + d | 0;
        c = c + t | 0;
        return (t >>> 0) / 4294967296;
    }
}

var _rand = _sfc32(0x9E3779B9, 0x243F6A88, 0xB7E15162, (1337 ^ 0xDEADBEEF));
for (let i = 0; i < 15; i++) _rand(); // warm up shuffle

// this is a drop-in replacement for math.random() which is non-seedable
export function pseudoRandom() {
    return _rand();
}