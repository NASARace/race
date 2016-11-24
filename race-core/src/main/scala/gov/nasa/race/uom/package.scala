package gov.nasa.race

/**
  * package units of measure provides value class abstractions for double quantities that
  * represent physical units.
  *
  * The reasons for not using the venerable squants library are that we need zero cost abstraction via
  * Scala value classes (unit types only exist at compile time), and we need to avoid dependencies on 3rd
  * party code that is not constantly updated/maintained (e.g. for Scala 2.12)
  *
  * Note that package uom follows the approach of using base units in its underlying representation, i.e. we do
  * not store units separately
  *
  * We also do not factor out common operators into universal traits. While this would give us unit-homogeneous
  * operators without duplicated code, it also would come at the cost of allocation
  */
package object uom {


  //--- used to avoid disambituities due to type erasure
  implicit object AngleDisambiguator
  implicit object LengthDisambiguator
  implicit object SpeedDisambiguator
  implicit object AreaDisambiguator
}
