package test.transformer

import org.scalatest.FunSuite
import transformer._

import scala.language.{higherKinds, implicitConversions, reflectiveCalls}
import scala.util.Try
import scalaz._
//import Scalaz._
import transformer.cwutil._
import transformer.cwmonad._

import scala.util.DynamicVariable
import scalaz.effect._
import scalaz.std.AllFunctions._
import scalaz.std.list._
import scalaz.syntax.foldable._

class CWMlessTest extends FunSuite{
  import cwmless._

  val RC = ReactiveContext

  val nocheckStrm:Stream[Context] = Continue +: Stream(Continue)

  val dat = new DynamicVariable("")

  def mytest[A](thunk: => A) = dat.withValue(""){thunk}

  def log(str: => String):Unit = {
    dat.value = dat.value + "_" + str
  }

  def testP:CW[Unit] = lift {
    unlift(log("n0") /+ (_ => log("f0")))
    unlift(log("n1") /+ (_ => log("f1")))
    unlift(log("n2") /+ (_ => log("f2")))
    unlift(log("n3") /+ (_ => log("f3")))
  }

  test("success"){
    mytest{
      testP.exec(RC(Continue))
      assert(dat.value == "_n0_n1_n2_n3")
    }

  }

  test("compensation"){
    mytest{
      testP.exec(RC(List(Continue,Continue,Abort)))
      assert(dat.value == "_n0_n1_f1_f0")
    }
  }

  test("val"){
    val testP1 = lift{
      val a = unlift((3) /+ ())
      val b = a * 4
      val c = unlift((b + 5) /+ ())
      c
    }

    val r = testP1.exec(RC(Continue))
    assert(r == \/-(17))
  }

}
