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
    }
}

const MaxSkipListDepth = 16;

class SkipList {
    constructor(isBefore, isSame) {
        this.isBefore = isBefore;
        this.isSame = isSame;

        this.head = new SkipListNode(null, new Array(MaxSkipListDepth));
        this.size = 0;
        this.depth = 0;
    }

    includes(data) {
        let n = this.head;
        for (let lvl = this.depth; lvl >= 0; lvl--) {
            while (n.next[lvl] && this.isBefore(n.next[lvl].data, data)) n = n.next[lvl];
        }
        n = n.next[0];
        return (n && this.isSame(data, n.data))
    }

    insert(data) {
        function randomLevel() {
            var lvl = 0;
            while (lvl < MaxSkipListDepth && Math.random() < 0.5) {
                lvl++;
            }
            return lvl;
        }

        let update = [];
        let n = this.head;

        for (let lvl = this.depth; lvl >= 0; lvl--) {
            while (n.next[lvl] && this.isBefore(n.next[lvl].data, data)) n = n.next[lvl];
            update[lvl] = n;
        }

        n = n.next[0];
        if (n && this.isSame(n.data, data)) {
            n.data = data;

        } else {
            let newDepth = randomLevel();
            if (newDepth > this.depth) { // add more layers
                for (let lvl = this.depth + 1; lvl <= newDepth; lvl++) {
                    update[lvl] = this.head;
                }
                this.depth = newDepth;
            }

            n = new SkipListNode(data, []);
            for (let lvl = 0; lvl <= newDepth; lvl++) {
                n.next[lvl] = update[lvl].next[lvl];
                update[lvl].next[lvl] = n;
            }
            this.size++;
        }
    }

    remove(data) {
        let update = [];
        let n = this.head;

        for (let lvl = this.depth; lvl >= 0; lvl--) {
            while (n.next[lvl] && this.isBefore(n.next[lvl].data, data)) n = n.next[lvl];
            update[lvl] = n;
        }

        n = n.next[0];
        if (n && this.isSame(n.data, data)) {
            for (let lvl = 0; lvl <= this.depth && update[lvl].next[lvl] === n; lvl++) {
                update[lvl].next[lvl] = n.next[lvl];
            }
            for (let lvl = this.depth; lvl >= 0 && this.head.next[lvl] == null; lvl--) this.depth--;

            this.size--;
            return true;

        } else {
            return false;
        }
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
        for (let lvl = this.depth; lvl >= 0; lvl--) this.head.next[lvl] = null;
        this.size = 0;
        this.depth = 0;
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