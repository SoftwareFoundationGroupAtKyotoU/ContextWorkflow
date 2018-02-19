import scalaz.effect._

package object contextworkflow {
  type CW[A] = cwmonad.CWMT[Unit,IO,Nothing,A]
  //type RC = ReactiveContext
  val RC = ReactiveContext
}
