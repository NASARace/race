package gov.nasa.race.common

/**
  * linear interpolant for time series data
  *
  * Note that we do not imply a specific time or value storage but require a TDataSource that maps logical indices
  * [0..n-1] to observation DataPoints
  */
final class LinTInterpolant [N<:Nat,T<:TDataPoint[N]](override val src: TDataSource[N,T]) extends TInterpolant[N,T](src) {
  val a: T = src.newDataPoint
  val b: T = src.newDataPoint
  val c: T = src.newDataPoint

  private def extrapolateLeft (t: Long): T = {
    src.getDataPoint(0, a)
    src.getDataPoint(1, b)
    val w: Double = (a.getTime - t).toDouble / (b.getTime - a.getTime)

    b -= a
    b *= w
    a -= b
    a.setTime(t)

    a
  }

  private def extrapolateRight (t: Long): T = {
    src.getDataPoint(n1 - 1, a)
    src.getDataPoint(n1, b)
    val w: Double = (t - b.getTime).toDouble / (b.getTime - a.getTime)

    c := b
    b -= a
    b *= w
    c -= b
    c.setTime(t)

    c
  }

  private def interpolate (i: Int, t: Long): T = {
    src.getDataPoint(i,a)

    if (t > a.getTime) {
      src.getDataPoint(i+1, b)
      val w: Double = (t - a.getTime).toDouble / (b.getTime - a.getTime)

      b -= a
      b *= w
      a += b
      a.setTime(t)
    }

    a
  }

  //--- public methods

  def eval (t: Long): T = {
    if (t >= tLeft) {
      if (t <= tRight) interpolate( findLeftIndex(t), t)
      else extrapolateRight(t)
    } else extrapolateLeft(t)
  }

  def iterator (tStart: Long, tEnd: Long, dt: Int): Iterator[T] = {
    def exact (t: Long, i: Int): T = { src.getDataPoint(i,a); a }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): T = {
      if (i >= 0) {
        if (i < n1)  interpolate(i, t) else extrapolateRight(t)
      } else extrapolateLeft(t)
    }
    new ForwardIterator(tStart, tEnd, dt)(exact)(approx)
  }

  def reverseIterator (tEnd: Long, tStart: Long, dt: Int): Iterator[T] = {
    def exact (t: Long, i: Int): T = { src.getDataPoint(i,a); a }
    def approx (tPrev: Long, t: Long, tNext: Long, i: Int): T = {
      if (i >= 0) {
        if (i < n1)  interpolate(i, t) else extrapolateRight(t)
      } else extrapolateLeft(t)
    }
    new ReverseIterator(tEnd, tStart, dt)(exact)(approx)
  }
}
