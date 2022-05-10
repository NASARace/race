package gov.nasa.race.util

import scala.annotation.tailrec
import scala.util.Random
import scala.collection.mutable.IndexedSeq
import scala.reflect.ClassTag

object SeqUtils {

  /**
    * Hoare's selection algorithm with Lomuto partitioning
    * NOTE - this partially sorts the provided Seq
    *
    * @param list  unsorted list to select from
    * @param k     requested index of sorted list [0..list.size-1]
    * @param ord   ordering to compare list elements
    * @tparam A    type of list elements
    * @return      k'th element of sorted list
    */
  def quickSelect[A] (list: IndexedSeq[A], k: Int)(implicit ord: Ordering[A]): A = {
    @inline def swap (list: IndexedSeq[A], i: Int, j: Int): Unit = {
      val tmp = list(j)
      list(j) = list(i)
      list(i) = tmp
    }

    def partition (list: IndexedSeq[A], left: Int, right: Int, pivotIndex: Int): Int = {
      val pivotVal = list(pivotIndex)
      swap(list,pivotIndex,right)
      var storeIndex = left
      for (i <- left until right) {
        if (ord.lt(list(i),pivotVal)){
          swap(list,storeIndex,i)
          storeIndex += 1
        }
      }
      swap(list,right,storeIndex)
      storeIndex
    }

    @tailrec def quickSelect (list: IndexedSeq[A], left: Int, right: Int, k: Int)(implicit ord: Ordering[A]): A = {
      if (left == right) {
        list(left)

      } else {
        val pivotIndex = partition(list,left,right,left + Random.nextInt(right+1 - left))
        if (k == pivotIndex) {
          list(k)
        } else if (k < pivotIndex) {
          quickSelect(list,left,pivotIndex-1,k)
        } else {
          quickSelect(list,pivotIndex+1,right,k)
        }
      }
    }

    val n = list.size
    if (k < 0 || k >= n) throw new IllegalArgumentException(s"k outside range 0..$n")
    quickSelect(list,0,n-1,k)(ord)
  }

  /**
    * simple quicksort to sort IndexSeq collections in place
    * (scala.util.Sorting only provides in-place sorting for arrays)
    */
  def quickSort[A] (list: IndexedSeq[A])(implicit ord: Ordering[A]): Unit = {
    @inline def swap (i: Int, j: Int): Unit = {
      val tmp = list(i)
      list(i) = list(j)
      list(j) = tmp
    }

    def qs(left: Int, right: Int): Unit = {
      if (left < right){
        val i = partition(left,right)
        qs(left,i-1)
        qs(i+1,right)
      }
    }

    def partition (left: Int, right: Int): Int = {
      val pivot = list(right)
      var i = left - 1

      var j = left
      while (j < right){
        if (ord.lteq(list(j),pivot)){
          i += 1
          swap(i,j)
        }
        j += 1
      }

      i += 1
      swap(i,right)
      i
    }

    qs(0,list.size-1)
  }

  def sortedArray[A: ClassTag] (it: Iterable[A])(sortFunc: (A,A)=>Boolean): Array[A] = {
    val a = it.toArray
    a.sortWith(sortFunc)
  }

  def sortedSeq[A] (it: Iterable[A])(sortFunc: (A,A)=>Boolean): Seq[A] = {
    val seq = it.toSeq
    seq.sortWith(sortFunc)
  }

  def optionToSeq[A](opt: Option[A]): Seq[A] = {
    opt match {
      case Some(a) => Seq(a)
      case None => Seq.empty
    }
  }
}
