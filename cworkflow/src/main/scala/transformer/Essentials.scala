package transformer

/**
  * Created by hinoue on 2017/06/15.
  */

import rescala._

import scala.language.implicitConversions
import scala.language.reflectiveCalls
import scala.language.higherKinds
import scala.language.existentials

class CWException extends Exception
case class PAbortE() extends Exception
object RestartE extends PAbortE
object AbortE extends CWException
object SuspendE extends CWException

trait Context
case class PAbort() extends Context
object Restart extends PAbort{
  override def toString: String = "R"
}
object Abort extends Context{
  override def toString: String = "A"
}
object Suspend extends Context{
  override def toString: String = "S"
}
object Continue extends Context{
  override def toString: String = "C"
}

// Additional contexts to be implemented
//object Fail extends Context // doesn't compensate
//object PartialRestart extends Context // do partial restart
//object PartialSuspend extends Context // do suspend at partial

trait ReactiveContext extends Iterator[Context]{
  def ccheck(): Context

  override def hasNext: Boolean = true
  override def next(): Context = ccheck()
//  override def toString(): String = {
//    this.toStream.take(30).toList.map(_.toString.head).mkString("")
//  }
}

object ReactiveContext{
  def fromSignal(sig: Signal[Context]): ReactiveContext = new ReactiveContext{
    def ccheck() = sig.now
  }

  def fromFun(fc: () => Context): ReactiveContext = new ReactiveContext{
    def ccheck() = fc()
  }

  // return the last context if s.tail is empty
  def fromStream(s: Stream[Context]): ReactiveContext = new ReactiveContext{
    var ptr:Stream[Context] = if(s.isEmpty) Stream(Continue) else s
    def ccheck():Context = {
      val ctx = ptr.headOption.getOrElse(Continue)
      if(!ptr.tail.isEmpty){
        ptr = ptr.tail
      }
      return ctx
    }
  }

  def fromList(l: List[Context]): ReactiveContext = new ReactiveContext{
    var ptr:List[Context] = if(l.isEmpty) List(Continue) else l
    def ccheck():Context = {
      val ctx = ptr.headOption.getOrElse(Continue)
      if(!ptr.tail.isEmpty){
        ptr = ptr.tail
      }
      return ctx
    }
  }

  def fromConstant(c: Context): ReactiveContext = new ReactiveContext{
    def ccheck() = c
  }

  def apply(l: List[Context]) = fromList(l)

  def apply(s: Stream[Context]) = fromStream(s)

  def apply(fc: () => Context) = fromFun(fc)

  def apply(c: Context) = fromConstant(c)

  def apply(sig: Signal[Context]) = fromSignal(sig)
}

trait Fix[F[_, _], A] {
  val out: F[Fix[F, A], A]
}

class In[F[_, _], A](k: F[Fix[F, A], A]) extends Fix[F, A] {
  val out: F[Fix[F, A], A] = k
}

object In{
  def apply[F[_,_],A](k: F[Fix[F, A], A]) = new In(k)
  //def apply[F[_,_],A](k: F[Fix[F, A], A]) = new In(k)
}

