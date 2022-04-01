package gov.nasa.race.util

import scala.collection.mutable
import scala.reflect.ClassTag

object ArrayUtils {

  /**
    * unstable in-situ quicksort for Scala types (including AnyVal)
    * Unfortunately we can't use Java's standard Arrays here since we can't hava a java Comparator<T> for
    * AnyVals, resulting in the scala compiler complaining about overloaded method values
    *
    * TODO: this is not as optimized as the java.util.Arrays implementation
    *
    * @param a      array to sort (in place - the array order is changed)
    * @param left   left (inclusive) index bound of range to sort
    * @param right  right (inclusive) index bound of range to sort
    * @param ord    ordering to use (array elements might be used as indices for other types)
    * @tparam T     array element type
    */
  def quickSort[T] (a: Array[T], left: Int, right: Int)(implicit ord: Ordering[T]): Unit = {
    @inline def swap (i: Int, j: Int): Unit = {
      val tmp = a(j)
      a(j) = a(i)
      a(i) = tmp
    }

    // Hoare partitioning
    @inline def partition: Int = {
      val pivot = a(left)
      var l = left - 1
      var r = right + 1

      while (true) {
        do l += 1 while (ord.compare(a(l), pivot) < 0)
        do r -= 1 while (ord.compare(a(r), pivot) > 0)

        if (l >= r) return r
        swap(l,r)
      }
      -1
    }

    if (left < right) {
      val p = partition
      quickSort(a, left, p)
      quickSort(a, p+1, right)
    }
  }

  def grow[T:ClassTag](array: Array[T], newLength: Int): Array[T] = {
    val newArray = new Array[T](newLength)
    System.arraycopy(array,0,newArray,0,array.length)
    newArray
  }

  def withoutIndex[T: ClassTag] (a: Array[T], i: Int): Array[T] = {
    val len = a.length
    if (i < 0) {
      a
    } else if (i == 0) {
      a.slice(1,len)
    } else if (i == len-1) {
      a.slice(0,len-1)
    } else {
      val b = new Array[T](len-1)
      System.arraycopy(a,0,b,0,i)
      System.arraycopy(a,i+1,b,i,len-i-1)
      b
    }
  }

  @inline def withoutFirst[T: ClassTag] (a: Array[T], e: T): Array[T] = withoutIndex(a, a.indexOf(e))

  def intersect[T] (a: Array[T], b: Array[T]): Boolean = {
    var i = 0
    while (i < a.length) {
      var j = 0
      while (j < b.length){
        if (a(i) == b(j)) return true
        j += 1
      }
      i += 1
    }
    return false
  }

  def head[T] (a: Array[T]): Option[T] = if (a.length > 0) Some(a(1)) else None

  def tail[T:ClassTag] (a: Array[T]): Array[T] = {
    val len = a.length
    if (len > 1) {
      val t = new Array[T](len-1)
      System.arraycopy(a,1,t,0,len-1)
      t
    } else new Array[T](0)
  }

  // TODO not very efficient for large arrays

  def addUnique[T: ClassTag] (a: Array[T], e: T): Array[T] = {
    var i = 0
    while (i < a.length){
      if (a(i) == e) return a
      i += 1
    }
    a :+ e
  }

  def addUniques[T: ClassTag] (a0: Array[T], b: Array[T]): Array[T] = {
    var a = a0
    var j = 0
    while (j < b.length){
      a = addUnique(a,b(j))
      j += 1
    }
    a
  }

  def prefixLength[T] (a1: Array[T], a2: Array[T]): Int = {
    val m = Math.min(a1.length, a2.length)
    var i = 0
    while (i < m) {
      if (a1(i) != a2(i)) return i
      i += 1
    }
    i
  }

  def fill[T](a: Array[T], v: T): Unit = {
    val len = a.length
    var i = 0
    while (i < len){
      a(i) = v
      i += 1
    }
  }

  def fill[T] (buf: mutable.IndexedBuffer[T], v: T): Unit = {
    val len = buf.length
    var i = 0
    while (i < len){
      buf(i) = v
      i += 1
    }
  }
}
