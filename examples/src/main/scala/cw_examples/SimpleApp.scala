package cw_examples

import contextworkflow._
import cwutil._

object SimpleApp extends App {
  var sum = 0

  def add(i: Int): CW[Unit] = {
    sum += i
  } /+ { _ => sum -= i }

  val add10: CW[Unit] = foreachCW(1 to 10)(add(_))

  val abrt: Stream[Context] = Stream(Continue, Continue, Continue, Continue, Abort)
  println("[Abort]")
  val r1 = add10.exec(abrt)
  println("sum = " + sum) // sum = 0

  println("[Continue]")
  val r2 = add10.exec()
  println("sum = " + sum) // sum = 55

  val sus: Stream[Context] = Stream(Continue, Continue, Continue, Continue, Suspend)
  println("[Reset sum]")
  sum = 0
  println("[Suspend]")
  val r3 = add10.exec(sus)
  println("sum = " + sum) // sum = 10
  r3.toEither.left.get.get.exec()
  println("[Continue]")
  println("sum = " + sum) // sum = 55
}