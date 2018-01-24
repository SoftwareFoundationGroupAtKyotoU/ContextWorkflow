package transformer

import scala.language.implicitConversions
import scala.language.reflectiveCalls
import scala.language.higherKinds
import scalaz._
import Scalaz._
import scalaz.effect.IO
import fwf._
import io.monadless._

import scala.util.{Try,Failure,Success}

/** black magic cast!! */
object cwfbmutil {

  val RC = ReactiveContext

  type CWFN[A] = fwf.CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A]
  type SUS[R] = Fix[CW[Unit,IO,?,?],R]

  val cwmless = new Monadless[CWFN]{
    def apply[T](v: => T): M[T] = IO(v) %% () // atom[Unit,IO,SUS[Nothing],T](IO(v))(_ => IO(()))

    def collect[A](l: List[M[A]]): M[List[A]] = l.foldLeft(atom[List[A]](IO(Nil))())((b, ma) => for{
      l0 <- b
      a <- ma
    } yield a :: l0).map(_.reverse)

    //def subW[T](body: T): CWFN[T] = sub(workflow(body))
  }

  def point[A](a: => A) = cwM.point(a)

  def foreach[A](l: Stream[A])(f: A => CWFN[Unit]): CWFN[Unit] =
    l.foldLeftM[CWFN[?], Unit](())((_, a) => f(a))

  def foldCW[A,B](l: List[A])(z: B)(f: (B,A) => CWFN[B]): CWFN[B] =
    l.foldLeftM[CWFN[?], B](z)(f)

  def compensateWith[A](na:IO[A])(ca:A => IO[Unit]): CWFN[A] = compL(na)(ca)

  val cp: CWFN[Unit] = checkpointL[Unit,IO,SUS[Nothing]]
  val checkpoint = cp

  def sub[A](cw: CWFN[A]):CWFN[A] = subL[Unit,IO,SUS[Nothing],A](cw)

  def atom[A](na:IO[A])(ca:A => IO[Unit] = (_:A) => IO(())): CWFN[A] = atomL(na)(ca)

  def atomic[A](cw: CWFN[A]): CWFN[A] = fwf.atomicL[Unit,IO,SUS[Nothing],A](cw)

  def nonatomic[A](cw: CWFN[A]): CWFN[A] = fwf.nonatomicL[Unit,IO,SUS[Nothing],A](cw)

  def throwError[A](s:Context): CWFN[A] = throwTError[IO,SUS[Nothing],A](s)

  // catch TransactionError in the normal action of pwf
  private def catchTE[A](cw: => CWFN[Try[A]]):CWFN[Try[A]] = {
    val recovery: PartialFunction[Try[A], CWFN[Unit]] = (t: Try[A]) => t match {
      case Failure(AbortE) => throwTError(Abort)
      case Failure(RestartE) => throwTError(Restart)
      //case Failure(SuspendTE) => throwSuspend()
      case _ => atom(IO(()))(_ => IO(()))
    }

    for {
      c <- cw
      _ <- recovery(c)
    } yield c
  }

  def finalizer[A](cw: CWFN[A], f: => Unit):CWFN[A] = sub{for{
    _ <- atom(IO(()))(_ => IO(f))
    x <- cw
    _ <- atom(IO(f))()
  } yield x}


  implicit def toCWFOps[A](cw: => CWFN[A]): CWFOps[A] =
    new CWFOps[A](cw)

  class CWFOps[A](cw: CWFN[A]) {
    /** programmable compensation */
    def %% (comp: A => IO[Unit])
    : CWFN[A] = for {
      res <- cw
      _ <- atomL(IO(res))(a => comp(a))
    } yield res

    /** programmable compensation */
    def %%(comp: IO[Unit])
    : CWFN[A] = for {
      res <- cw
      _ <- atomL(IO(res))(a => comp)
    } yield res

    /** programmable compensation */
    def \\ (comp: A => Unit = _ => ()): CWFN[A] = %%(a => IO(comp(a)))

    // def \\ () : CWFN[A] = %%(_ => IO(()))

    /** programmable compensation */
    def /+ (comp: => A => Unit = _ => ()): CWFN[A] = \\(comp)

    /** programmable compensation. lower than >>= and => */
    def |+ (comp: => A => Unit = _ => ()): CWFN[A] = \\(comp)

    /** finally (to be deprecated!) */
    def /- (f: => Unit): CWFN[A] = finalizer(cw, f)

    /** programmable compensation */
    def ^/ (comp: => A => Unit = _ => ()) = \\(comp)

    /** finally */
    def ^/+ (f: => Unit): CWFN[A] = finalizer(cw, f)

    def join[B](implicit ev: A =:= CWFN[B])
    : CWFN[B] = {
      val M = cwM[Unit, IO, Fix[CW[Unit, IO, ?, ?], Nothing]]
      M.join(M.map(cw)(ev(_)))
    }
  }


  implicit def toCWFIOOps[A](proc: => IO[A]): CWFIOOps[A] =
    new CWFIOOps[A](proc)

  implicit def toCWFIOOps2[A](proc: => A): CWFIOOps[A] =
    new CWFIOOps[A](IO(proc))

  implicit def toCWFIOTryOps2[A](proc: => A): CWFIOTryOps[A] =
    new CWFIOTryOps[A](IO(Try(proc)))

  // make user-thrown exception enable
  class CWFIOTryOps[A](t: IO[Try[A]]){
    /** exceptional compensation */
    def \~\(comp: => A => Unit) : CWFN[A] = %~%(a => IO(comp(a)))

    /** exceptional compensation */
    def /~ (comp: => A => Unit = _ => ()) : CWFN[A] = \~\(comp)

    /** lower than >>= and => */
    def |~ (comp: => A => Unit = _ => ()): CWFN[A] = \~\(comp)

    /** exceptional compensation of low precedence */
    def ^/~ (comp: => A => Unit = _ => ()) = \~\(comp)

    def %~%(comp: => A => IO[Unit]) : CWFN[A] =
      for {
        tried <- compL(t)(_ match {
          case Success(a) => comp(a)
          case Failure(e) => IO(())
        })
        a <- tried match {
          case Failure(AbortE) => throwError[A](Abort)
          case Failure(RestartE) => throwError[A](Restart)
          case Success(a) => IO(a) %% ()
          case Failure(e) => IO[A]{throw e} %% ()
        }
      } yield a
  }

  class CWFIOOps[A](t: IO[A]) {

    def %% (comp: A => IO[Unit] = _ => IO(())): CWFN[A] =
      compL(t)(a => comp(a))

    def %% (comp: IO[Unit]): CWFN[A] =
      compL(t)(_ => comp)

    def \\ (comp: => A => Unit = _ => ()): CWFN[A] =
      compL(t)(a => IO(comp(a)))

    //    def \\[R](comp: => A => IO[Unit]): CWF[R,A] =
    //      compL(t)(a => comp(a))

    //def \\ () :CWFN[A] = \\((_: A) => ())

    def /+ (comp: => A => Unit = _ => ()): CWFN[A] = \\(comp)

    /** lower than >>= and => */
    def |+ (comp: => A => Unit = _ => ()): CWFN[A] = \\(comp)

    /** compensation of low precedence */
    def ^/ (comp: => A => Unit = _ => ()) = \\(comp)

    // def /+ () :CWFN[A] = \\()
  }

}



// old
object cwfutil {

  val RC = ReactiveContext
  
  type CWF[R,A] = fwf.CW[Unit,IO,Fix[CW[Unit,IO,?,?],R],A]
  type SUS[R] = Fix[CW[Unit,IO,?,?],R]

  def foreach[R,A](l: Stream[A])(f: A => CWF[R,Unit]): CWF[R,Unit] =
    l.foldLeftM[CWF[R,?], Unit](IO(()) %% ())((_, a) => f(a))

  def compensateWith[R,A](na:IO[A])(ca:A => IO[Unit]): CWF[R,A] = compL(na)(ca)

  def cp[R]: CWF[R,Unit] = checkpointL[Unit,IO,SUS[R]]

//  def throwTError[A](s:Context): CWF[A,Unit] = {
//    type M[A] = IO[A]
//    val M = IO.ioMonad
//    type S = Fix[CW[Unit, M, ?, ?], A]
//    type R = ResP[M[Unit], S]
//    type SS[X] = CompL[M, S, X]
//    type FF[X] = ReaderT[M, Sig, X]
//    type MM[X] = EitherT[FF, InSubL[R], X]
//    type STK = InSubL[R]
//
//    CW(FreeT.liftM[SS,MM,Unit](s match {
//      case Abort => erME[Unit, IO, STK].raiseError[Unit](fInSubL.point(Aborting[M[Unit], S](M.point(()))))
//      case Restart => erME[Unit, M, STK].raiseError[Unit](fInSubL.point(PAborting[M[Unit], S](None, M.point(()))))
////      case Suspend => erME[Unit, M, STK].raiseError[A](fInSubL.point(Suspending[M[Unit], S](
////        In[CW[Unit, M, ?, ?], A](CW[Unit, M, S, A](FreeT.roll[SS, MM, A](Check[M, S, FreeT[SS, MM, A]](liftM[SS, MM, A](k)(erME))))))))
//    }))
//  }


  implicit def toCWFFOps[R, A](cw: => CWF[R, A]): CWFfOps[R, A] =
    new CWFfOps[R, A](cw)

  class CWFfOps[R, A](cw: CWF[R, A]) {
    def %%(comp: A => IO[Unit])
    : CWF[R, A] = for {
      res <- cw
      _ <- atomL(IO(res))(a => comp(a))
    } yield res

    def %%(comp: IO[Unit])
    : CWF[R, A] = for {
      res <- cw
      _ <- atomL(IO(res))(a => comp)
    } yield res

    def \\(comp: A => IO[Unit])
    : CWF[R, A] = %%(comp)

    def \\(comp: IO[Unit])
    : CWF[R, A] = %%(comp)
    
    def join[B](implicit ev: A =:= CWF[R, B])
    : CWF[R, B] = {
      val M = cwM[Unit, IO, Fix[CW[Unit, IO, ?, ?], R]]
      M.join(M.map(cw)(ev(_)))
    }
  }


  implicit def toCWFIOOps[A](proc: => IO[A]): CWFIOOps[A] =
    new CWFIOOps[A](proc)

  implicit def toCWFIOOps2[A](proc: => A): CWFIOOps[A] =
    new CWFIOOps[A](IO(proc))

  class CWFIOOps[A](t: IO[A]) {

    def %%[R](comp: A => IO[Unit] = _ => IO(())): CWF[R,A] =
      compL(t)(a => comp(a))

    def %%[R](comp: IO[Unit]): CWF[R,A] =
      compL(t)(_ => comp)

    def \\[R](comp: => A => Unit): CWF[R,A] =
      compL(t)(a => IO(comp(a)))

//    def \\[R](comp: => A => IO[Unit]): CWF[R,A] =
//      compL(t)(a => comp(a))

    def \\[R]: CWF[R, A] = \\((_: A) => ())
  }

}  
