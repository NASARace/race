package gov.nasa.race.common

/**
  * to be used to implement overloaded methods for different generic types, which does not
  * work without implicit conversion because of JVM type erasure
  */
object ImplicitGenerics {

  implicit class StringStringPair (val tuple: (String,String))
  implicit class StringBooleanPair (val tuple: (String,Boolean))
  implicit class StringIntPair (val tuple: (String,Int))
  implicit class StringLongPair (val tuple: (String,Long))
  implicit class StringDoublePair (val tuple: (String,Double))

  implicit class StringIterable (val iterable: Iterable[String])
  implicit class BooleanIterable (val iterable: Iterable[Boolean])
  implicit class IntIterable (val iterable: Iterable[Int])
  implicit class LongIterable (val iterable: Iterable[Long])
  implicit class DoubleIterable (val iterable: Iterable[Double])


  implicit class StringStringPairIterable (val iterable: Iterable[(String,String)])
  implicit class StringBooleanPairIterable (val iterable: Iterable[(String,Boolean)])
  implicit class StringIntPairIterable (val iterable: Iterable[(String,Int)])
  implicit class StringLongPairIterable (val iterable: Iterable[(String,Long)])
  implicit class StringDoublePairIterable (val iterable: Iterable[(String,Double)])

}