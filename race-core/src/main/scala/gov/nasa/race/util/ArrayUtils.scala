package gov.nasa.race.util

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
}
