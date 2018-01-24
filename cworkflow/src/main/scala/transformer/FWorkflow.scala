package transformer

import scala.language.implicitConversions
import scala.language.reflectiveCalls
import scala.language.higherKinds
import scalaz._
import Scalaz._
import scala.util.Try
import scalaz.effect.IO

object fwf {
  import FreeT._
  //import Free._



  sealed trait ResP[E,S]
  case class Aborting[E,S](e:E) extends ResP[E,S]
  case class PAborting[E,S](s:Option[S],e:E) extends ResP[E,S]
  case class Suspending[E,S](s:S) extends ResP[E,S]

  implicit def fResP[E] = new Functor[ResP[E,?]]{
    override def map[A, B](fa: ResP[E, A])(f: A => B): ResP[E, B] = fa match {
      case Aborting(e) => Aborting(e)
      case PAborting(s,e) => PAborting(s.map(f),e)
      case Suspending(s) => Suspending(f(s))
    }
  }

  type Sig = ReactiveContext

  sealed trait CompL[M[_],S,A]
  case class Comp[M[_],S,A](m:M[Unit], a:A) extends CompL[M,S,A]
  case class SubB[M[_],S,A](a:A) extends CompL[M,S,A]
  case class SubE[M[_],S,A](a:A) extends CompL[M,S,A]
  case class Cp[M[_],S,A](a:A) extends CompL[M,S,A]
  case class Cpn[M[_],S,A](s:S,a:A) extends CompL[M,S,A]
  // checkOpt: if None or Some(true) => check else noncheck
  case class Check[M[_],S,A](a:A, checkOpt : Option[Boolean] = None) extends CompL[M,S,A]

  implicit def fCompL[M[_],S] = new Functor[CompL[M,S,?]]{
    override def map[A, B](fa: CompL[M, S, A])(f: A => B): CompL[M, S, B] = fa match {
      case Comp(m,a) => Comp(m,f(a))
      case SubB(a) => SubB(f(a))
      case SubE(a) => SubE(f(a))
      case Cp(a) => Cp(f(a))
      case Cpn(s,a) => Cpn(s,f(a))
      case Check(a,b) => Check(f(a),b)
    }
  }

  implicit def bifCompL[M[_],S] = new Bifunctor[CompL[M,?,?]]{
    override def bimap[A, B, C, D](fab: CompL[M, A, B])(f: A => C, g: B => D): CompL[M, C, D] = fab match {
      case Comp(m,a) => Comp(m,g(a))
      case SubB(a) => SubB(g(a))
      case SubE(a) => SubE(g(a))
      case Cp(a) => Cp(g(a))
      case Cpn(s,a) => Cpn(f(s),g(a))
      case Check(a,b) => Check(g(a),b)
    }
  }

  sealed trait InSubL[A]
  case class InSub[A](n:InSubL[A]) extends InSubL[A]
  case class NonSub[A](a:A) extends InSubL[A]

  implicit val fInSubL = new Monad[InSubL]{
    override def map[A, B](fa: InSubL[A])(f: A => B): InSubL[B] = fa match {
      case InSub(n) => InSub(map(n)(f))
      case NonSub(a) => NonSub(f(a))
    }

    override def point[A](a: => A): InSubL[A] = NonSub(a)

    override def bind[A, B](fa: InSubL[A])(f: A => InSubL[B]): InSubL[B] = fa match {
      case InSub(n) => InSub(bind(n)(f))
      case NonSub(a) => f(a)
    }
  }

  case class CW[E,M[_],S,A](runCW: FreeT[CompL[M,S,?],EitherT[ReaderT[M,Sig,?],InSubL[ResP[M[E],S]],?],A]){

    def flatMap[B](f: (A) => CW[E, M, S, B])(implicit M: Monad[M]) = cwM[E,M,S].bind[A,B](this)(f)

    def map[B](f:A => B)(implicit M: Monad[M]) = cwM[E,M,S].map(this)(f)

    def runM(st: Sig)
           (implicit M:Monad[M], M1: BindRec[M], ev: CW[E,M,S,A] =:= CW[Unit,M,Fix[CW[Unit,M,?,?],A],A])
    : M[\/[Option[Fix[CW[Unit,M,?,?],A]],A]] = {
      myrun[M,A](st)(ev(this))
    }

    def run(st: Sig)
           (implicit ev: CW[E,M,S,A] =:= CW[Unit,IO,Fix[CW[Unit,IO,?,?],A],A])
    : \/[Option[Fix[CW[Unit,IO,?,?],A]],A] = {
      myrun[IO,A](st)(ev(this)).unsafePerformIO()
    }

    // black magic with cast
    def runBM(st: Sig = ReactiveContext(Continue))
             (implicit ev: CW[E,M,S,A] =:= CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A])
    : \/[Option[CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A]],A] = {
      val r = myrun[IO,A](st)(ev(this).asInstanceOf[CW[Unit,IO,Fix[CW[Unit,IO,?,?],A],A]]).unsafePerformIO()
      r.leftMap(opt => opt.map(_.out.asInstanceOf[CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A]]))
    }

    def runBMIO(st: Sig = ReactiveContext(Continue))
             (implicit ev: CW[E,M,S,A] =:= CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A])
    : IO[\/[Option[CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A]],A]] = {
      val r = myrun[IO,A](st)(ev(this).asInstanceOf[CW[Unit,IO,Fix[CW[Unit,IO,?,?],A],A]])
      r.map(_.leftMap(opt => opt.map(_.out.asInstanceOf[CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A]])))
    }

    def runBMIOReturnsContext(st: Sig)
                           (implicit ev: CW[E,M,S,A] =:= CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A])
    : IO[(\/[Option[CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A]],A],Context)] = {
      val r = myrunReturnsContext[IO,A](st)(ev(this).asInstanceOf[CW[Unit,IO,Fix[CW[Unit,IO,?,?],A],A]])
      r.map(rr => (rr._1.leftMap(opt => opt.map(_.out.asInstanceOf[CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A]])),rr._2))
    }

    def runBMReturnsContext(st: Sig)
                           (implicit ev: CW[E,M,S,A] =:= CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A])
    : (\/[Option[CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A]],A],Context) = {
      val r = myrunReturnsContext[IO,A](st)(ev(this).asInstanceOf[CW[Unit,IO,Fix[CW[Unit,IO,?,?],A],A]]).unsafePerformIO()
      (r._1.leftMap(opt => opt.map(_.out.asInstanceOf[CW[Unit,IO,Fix[CW[Unit,IO,?,?],Nothing],A]])),r._2)
    }

  }

  type FS[M[_],S,A] = CompL[M,S,A]
  type FM[E,M[_],S,A] = EitherT[ReaderT[M,Sig,?],InSubL[ResP[M[E],S]],A]

  implicit def erME[E,M[_],S](implicit M: Monad[M])
  : MonadError[EitherT[ReaderT[M, Sig, ?], S, ?], S] = EitherT.eitherTMonadError[ReaderT[M, Sig, ?],S]

  def erMBR[E,M[_],S](implicit M:Monad[M], M1: BindRec[M]): BindRec[EitherT[ReaderT[M, Sig, ?], S, ?]] = {
    implicit val rMBR = ReaderT.kleisliBindRec[M,Sig]
    EitherT.eitherTBindRec[ReaderT[M, Sig, ?],S]
  }
    //EitherT.eitherTMonad[ReaderT[M, Sig, ?], A](ReaderT.kleisliMonadReader)

//  implicit def fCW[E,M[_],S](implicit M:Monad[M]) = new Functor[CW[E,M,S,?]]{
//    override def map[A, B](fa: CW[E, M, S, A])(f: A => B): CW[E, M, S, B] = CW(fa.runCW.map(f))
//  }

  implicit def cwM[E,M[_],S](implicit M:Monad[M]) = new Monad[CW[E,M,S,?]]{
    //override def map[A, B](fa: CW[E, M, S, A])(f: A => B): CW[E, M, S, B] = CW(fa.runCW.map(f))

    override def bind[A, B](fa: CW[E, M, S, A])(f: A => CW[E, M, S, B]): CW[E, M, S, B] = CW[E,M,S,B](fa.runCW.flatMap(a => f(a).runCW))

    override def point[A](a: => A): CW[E, M, S, A] = CW[E,M,S,A](FreeT.point[CompL[M,S,?],EitherT[ReaderT[M,Sig,?],InSubL[ResP[M[E],S]],?],A](a))
  }

  implicit def bifCW[E,M[_]](implicit M:Monad[M]) = new Bifunctor[CW[E,M,?,?]]{
    override def bimap[A, B, C, D](fab: CW[E, M, A, B])(f: A => C, g: B => D): CW[E, M, C, D] = {
      val ftm1 = fab.runCW
      val ftm2 = ftm1.map(g)
      val ftm3 = ftm2.hoistN[FM[E,M,C,?]](new ~>[FM[E,M,A,?],FM[E,M,C,?]]{
        override def apply[T](fa: FM[E, M, A, T]): FM[E, M, C, T] = fa.bimap(_.map(_.map(f)),x => x)
      })
      CW[E,M,C,D](ftm3.interpretS[CompL[M,C,?]](new ~>[CompL[M,A,?],CompL[M,C,?]]{
        override def apply[T](fa: CompL[M, A, T]): CompL[M, C, T] = bifCompL.bimap(fa)(f,x=>x)
      }))
    }
  }

  def ask[E,M[_],S,A](implicit M:Monad[M]): CW[E,M,S,Sig] = {
    type R = ResP[M[E], S]
    type SS[X] = CompL[M, S, X]
    type FF[X] = ReaderT[M, Sig, X]
    type MM[X] = EitherT[FF, InSubL[R], X]
    type STK = InSubL[R]

    CW[E,M,S,Sig](FreeT.liftM[SS,MM,Sig](Kleisli.ask[M, Sig].liftM[EitherT[?[_], STK, ?]]))
  }

  def compL[E,M[_],S,A](na:M[A])(ca:A => M[Unit])(implicit M:Monad[M]): CW[E,M,S,A] = {
    CW[E,M,S,A](for{
      _ <- liftF[FS[M,S,?],FM[E,M,S,?],Unit](Check(()))
      x <- liftM[FS[M,S,?],FM[E,M,S,?],A](na.liftM[ReaderT[?[_],Sig,?]].liftM[EitherT[?[_],InSubL[ResP[M[E],S]],?]]) //.liftM[FreeT[CompL[M,S,?],?[_],?]]
      _ <- liftF[FS[M,S,?],FM[E,M,S,?],Unit](Comp(ca(x),()))
    } yield x)
  }

  def atomL[E,M[_],S,A](na:M[A])(ca:A => M[Unit])(implicit M:Monad[M]): CW[E,M,S,A] = {
    CW[E,M,S,A](for{
      x <- liftM[FS[M,S,?],FM[E,M,S,?],A](na.liftM[ReaderT[?[_],Sig,?]].liftM[EitherT[?[_],InSubL[ResP[M[E],S]],?]]) //.liftM[FreeT[CompL[M,S,?],?[_],?]]
      _ <- liftF[FS[M,S,?],FM[E,M,S,?],Unit](Comp(ca(x),()))
    } yield x)
  }

  def checkpointL[E,M[_],S](implicit M:Monad[M]): CW[E,M,S,Unit] = CW[E,M,S,Unit](liftF[FS[M,S,?],FM[E,M,S,?],Unit](Cp(())))

  def subL[E,M[_],S,A](cw :CW[E,M,S,A])(implicit M:Monad[M]): CW[E,M,S,A] = CW[E,M,S,A]{
    for{
      _ <- liftF[FS[M,S,?],FM[E,M,S,?],Unit](SubB(()))
      r <- cw.runCW
      _ <- liftF[FS[M,S,?],FM[E,M,S,?],Unit](SubE(()))
    } yield r
  }

  def extendSuspending[E,M[_],S,A,F1[_],F[_],A1]
  (c: FreeT[CompL[M,Fix[CW[E,M,?,?],A1],?], EitherT[ReaderT[M,Sig,?],InSubL[ResP[M[E],Fix[CW[E,M,?,?],A1]]],?],A])
  (err: F1[F[Fix[CW[E,M,?,?],A1]]])
  (implicit M:Monad[M], F:Functor[F], F1:Functor[F1])
  : F1[F[Fix[CW[E,M,?,?],A1]]] = {
    //def extendSuspending_(c)()

    err.map(e => e.map(s => In[CW[E,M,?,?],A1](CW[E,M,Fix[CW[E,M,?,?],A1],A1](c.flatMap(_ => s.out.runCW)))))
  }

  def expandSuspending[E,M[_],S,A,B](post: A => CW[E,M,Fix[CW[E,M,?,?],B],B])
                                    (fcw: Fix[CW[E,M,?,?],A])
                                    (implicit M:Monad[M])
  : Fix[CW[E,M,?,?],B] = {
    In[CW[E,M,?,?],B](bifCW.bimap[Fix[CW[E,M,?,?],A],A,Fix[CW[E,M,?,?],B],A](fcw.out)(expandSuspending(post) _,a => a).flatMap[B](post))
  }

//  def bmToExecutable[E,M[_],S,A,B](cw: CW[E,M,Fix[CW[E,M,?,?],Nothing],A])
//                                  (implicit M:Monad[M])
//  : CW[E,M,Fix[CW[E,M,?,?],A],A] = {
//    In[CW[E,M,?,?],B](
//      bifCW.bimap[Fix[CW[E,M,?,?],Nothing],A,Fix[CW[E,M,?,?],A],A](cw)(_.asInstanceOf[Fix[CW[E,M,?,?],A]], a => a)
//    )
//  }

//  def expandSuspending[E,M[_],S,A,B](post: A => CW[E,M,_,B])(fcw: Fix[CW[E,M,?,?],A]): Fix[CW[E,M,?,?],B] = {
//    bifCW.bimap[Fix[CW[E,M,?,?],A],B,Fix[CW[E,M,?,?],B],B](fcw.out.flatMap[B](post))(expandSuspending(post),a => a)
//  }


  def setRestart[E,S](s:S)(sl:InSubL[ResP[E,S]]):InSubL[ResP[E,S]] = sl match {
    case NonSub(PAborting(None,cp)) => NonSub(PAborting(Some(s),cp))
    case x => x
  }

  def throwTError[M[_],S,A](s:Context, susopt: Option[S] = None)(implicit M: Monad[M]): CW[Unit,M,S,A] = {
    //type S = Fix[CW[Unit, M, ?, ?], A]
    type R = ResP[M[Unit], S]
    type SS[X] = CompL[M, S, X]
    type FF[X] = ReaderT[M, Sig, X]
    type MM[X] = EitherT[FF, InSubL[R], X]
    type STK = InSubL[R]

    CW[Unit,M,S,A](FreeT.liftM[SS,MM,A](
      (s,susopt) match {
        case (Abort, _) => erME[Unit, M, STK].raiseError[A](fInSubL.point(Aborting[M[Unit], S](M.point(()))))
        case (Restart, None) => erME[Unit, M, STK].raiseError[A](fInSubL.point(PAborting[M[Unit], S](None, M.point(()))))
        //case Suspend => Kleisli.local[M,A,Sig](_ => ReactiveContext(Suspend))()
        case (Restart, sus) => erME[Unit, M, STK].raiseError[A](fInSubL.point(PAborting[M[Unit], S](sus, M.point(()))))
        case (Suspend, Some(sus)) => erME[Unit, M, STK].raiseError[A](fInSubL.point(Suspending[M[Unit], S](sus)))
        case (Suspend, None) => ???
        case _ => ???
      }))
  }

  def atomize[M[_],S] =
    new (CompL[M,S,?] ~> CompL[M,S,?]) {
      def apply[A](c: CompL[M,S,A]): CompL[M,S,A] = c match {
        case Check(a,None) => Check(a,Some(false))
        case _ => c
      }
    }


  def atomicL[E,M[_], S, A](cw: CW[E, M, S, A])
                           (implicit M: Monad[M])
  : CW[E, M, S, A] = {
    CW[E,M,S,A](cw.runCW.interpretS[CompL[M,S,?]](atomize[M,S]))
  }

  def nonatomicL[E, M[_], S, A](cw: CW[E, M, S, A])
                               (implicit M: Monad[M])
  : CW[E, M, S, A] = CW[E,M,S,A](cw.runCW.interpretS[CompL[M,S,?]](new (CompL[M,S,?] ~> CompL[M,S,?]){
    def apply[A](c:CompL[M,S,A]):CompL[M,S,A] = c match {
      case Check(a,None) => Check(a,Some(true))
      case _ => c
    }
  }))


  def runCompL[M[_],A](s: Fix[CW[Unit,M,?,?],A])(M1: BindRec[M])(implicit M:Monad[M])
  : EitherT[ReaderT[M,Sig,?],InSubL[ResP[M[Unit],Fix[CW[Unit,M,?,?],A]]],A] = {
    type S = Fix[CW[Unit, M, ?, ?], A]
    type R = ResP[M[Unit], S]
    type SS[X] = CompL[M, S, X]
    type FF[X] = ReaderT[M, Sig, X]
    type MM[X] = EitherT[FF, InSubL[R], X]
    type STK = InSubL[R]

    implicit val erMBR_ = erMBR[Unit,M,STK](M,M1)

    //implicit val fc = fCompL[M,S]

    //implicit val fM:Functor[M] = M

    def runCompL__[F](cl: SS[FreeT[SS,MM,A]]): MM[FreeT[SS,MM,A]] = cl match {
      case Comp(c, k) => EitherT[FF, STK, A] {
        for {
          ev <- k.runM(runCompL__)(fCompL,erMBR_,erME).run
        } yield ev match {
          case \/-(_) => ev //ReaderT[M,Sig,ESTK](_ => M.point(ev))
          case -\/(err) => extendSuspending[Unit, M, S, Unit, InSubL, ResP[M[Unit], ?], A](liftF[SS, MM, Unit](Comp[M, S, Unit](c, ())))(err) match {
            case NonSub(p) => p match {
              case Aborting(cp) => {
//                println("aborting");cp.asInstanceOf[IO[Unit]].unsafePerformIO();
//                c.asInstanceOf[IO[Unit]].unsafePerformIO();
                \/.left(NonSub(Aborting[M[Unit], S](M.bind(cp)(res => M.bind(c)(_ => M.point(res))))))
              }
              case PAborting(None, cp) => \/.left(NonSub(PAborting[M[Unit], S](None, M.bind(cp)(res => M.bind(c)(_ => M.point(res))))))
              //case _ => ev
              case PAborting(Some(sp), cp) => \/.left(NonSub(PAborting[M[Unit], S](Some(sp), cp)))
              case Suspending(sp) => \/.left(NonSub(Suspending[M[Unit], S](sp)))
            }
            case _ => ev //ReaderT[M,Sig,ESTK](_ => M.point(ev))
          }
        }
      }.map(a => FreeT.point[SS,MM,A](a))

      case SubB(k) => EitherT[FF, STK, A]{
        for{
          ev <- k.runM(runCompL__).run
        } yield ev match {
          case \/-(a) => ev
          case -\/(err) => extendSuspending[Unit, M, S, Unit, InSubL, ResP[M[Unit], ?], A](liftF[SS, MM, Unit](SubB[M, S, Unit](())))(err) match {
            case InSub(m) => \/.left[STK,A](m)
            case x => \/.left(x)
          }
        }
      }.map(a => FreeT.point[SS,MM,A](a))

      case SubE(k) => EitherT[FF, STK, A]{
        for{
          ev <- k.runM(runCompL__).run
        } yield ev match {
          case \/-(_) => ev
          case -\/(err) => \/.left(InSub(extendSuspending[Unit, M, S, Unit, InSubL, ResP[M[Unit], ?], A](liftF[SS, MM, Unit](SubE[M, S, Unit](())))(err)))
        }
      }.map(FreeT.point[SS,MM,A])

      case Check(k0,Some(false)) => k0.runM(runCompL__).map(FreeT.point[SS,MM,A])

      case Check(k0,_) => {
        val k = k0.runM(runCompL__)

        Kleisli.ask[M, Sig].liftM[EitherT[?[_], STK, ?]].flatMap[A]{ h =>
          h.ccheck() match {
            case Abort => erME[Unit,M,STK].raiseError[A](fInSubL.point(Aborting[M[Unit], S](M.point(()))))
            case Restart => erME[Unit,M,STK].raiseError[A](fInSubL.point(PAborting[M[Unit], S](None, M.point(()))))
            case Suspend => erME[Unit,M,STK].raiseError[A](fInSubL.point(Suspending[M[Unit], S](
              In[CW[Unit, M, ?, ?], A](CW[Unit, M, S, A](FreeT.roll[SS, MM, A](Check[M, S, FreeT[SS, MM, A]](liftM[SS, MM, A](k)(erME))))))))
            case Continue =>  k //; Kleisli.local[MM,A,Sig](h)(k) // local tail k
          }
        }.map(FreeT.point[SS,MM,A])
      }

      case Cp(k) => EitherT[FF, STK, A] {
        def kk = k.runM(runCompL__)
        for {
          r <- kk.run
        } yield r match {
          case \/-(_) => r
          case -\/(err) => {
            val s = In[CW[Unit, M, ?, ?], A](CW[Unit, M, S, A](liftM[SS, MM, A](kk)(erME)))
            val kp = liftF[SS, MM, Unit](Cpn(s, ()))
            val rs = In[CW[Unit, M, ?, ?], A](CW[Unit, M, S, A](kp.flatMap(_ => s.out.runCW)))
            \/.left(setRestart[M[Unit], S](rs)(extendSuspending[Unit, M, S, Unit, InSubL, ResP[M[Unit], ?], A](kp)(err)))
          }
        }
      }.map(FreeT.point[SS,MM,A])

      case Cpn(s, k) => EitherT[FF, STK, A] {
        for {
          r <- k.runM(runCompL__).run
        } yield r match {
          case \/-(_) => r
          case -\/(err) => {
            val kp = liftF[SS, MM, Unit](Cpn(s, ()))
            val rs = In[CW[Unit, M, ?, ?], A](CW[Unit, M, S, A](kp.flatMap(_ => s.out.runCW)))
            \/.left(setRestart[M[Unit], S](rs)(extendSuspending[Unit, M, S, Unit, InSubL, ResP[M[Unit], ?], A](kp)(err)))
          }
        }
      }.map(FreeT.point[SS,MM,A])
    }
//      c match {
//      case Comp(c, k) =>
//
//    }

    s.out.runCW.runM(runCompL__)
  }

  private def retract[A](isl: InSubL[A]):A = isl match{
    case NonSub(a) => a
    case InSub(as) => retract(as)
  }


  def myrunReturnsContext[M[_], A](st: Sig)
                    (cw: CW[Unit,M,Fix[CW[Unit,M,?,?],A],A])
                    (implicit M: Monad[M], M1: BindRec[M])
  : M[(\/[Option[Fix[CW[Unit,M,?,?],A]],A], Context)] = {
    type S = Fix[CW[Unit,M,?,?],A]

    M.bind(runCompL[M,A](In[CW[Unit,M,?,?],A](cw))(M1).run.run(st) ){
      case \/-(v) => M.point((\/-(v),Continue))
      case -\/(err) => retract(err) match {
        case Aborting(cp) => M.bind(cp){_ => M.point((-\/(None),Abort))}
        case Suspending(sp) => M.point((-\/(Some(sp)),Suspend))
        case PAborting(None,cp) => M.bind(cp)(_ => M.point((-\/(None),Restart)))
        case PAborting(Some(sp),cp) => M.bind(cp)(_ => M.point((-\/(Some(sp)),Restart)))
      }
    }
  }

  def myrun[M[_], A](st: Sig)
                    (cw: CW[Unit,M,Fix[CW[Unit,M,?,?],A],A])
                    (implicit M: Monad[M], M1: BindRec[M])
  : M[\/[Option[Fix[CW[Unit,M,?,?],A]],A]] = {
    type S = Fix[CW[Unit,M,?,?],A]

    M.bind(runCompL[M,A](In[CW[Unit,M,?,?],A](cw))(M1).run.run(st) ){
      case \/-(v) => M.point(\/-(v))
      case -\/(err) => retract(err) match {
        case Aborting(cp) => M.bind(cp){_ => M.point(-\/(None))}
        case Suspending(sp) => M.point(-\/(Some(sp)))
        case PAborting(None,cp) => M.bind(cp)(_ => M.point(-\/(None)))
        case PAborting(Some(sp),cp) => M.bind(cp)(_ => M.point(-\/(Some(sp))))
      }
    }
  }

  def myrunL[M[_], A](sl: List[Sig])
                     (cw: CW[Unit,M,Fix[CW[Unit,M,?,?],A],A])
                     (implicit M: Monad[M], M1: BindRec[M])
  : M[\/[Option[Fix[CW[Unit,M,?,?],A]],A]] = {
    type S = Fix[CW[Unit,M,?,?],A]
    val RC = ReactiveContext

    val next = sl match{
      case (_ :: Nil) | Nil => List(RC(Continue))
      case h :: t => t
    }

    M.bind(runCompL[M,A](In[CW[Unit,M,?,?],A](cw))(M1).run.run(sl.headOption.getOrElse(RC(Continue)))){
      case \/-(v) => M.point(\/-(v))
      case -\/(err) => retract(err) match {
        case Aborting(cp) => M.bind(cp)(_ => M.point(-\/(None)))
        case Suspending(sp) => myrunL[M,A](next)(sp.out) //M.point(-\/(Some(sp)))
        case PAborting(None,cp) => M.bind(cp)(_ => myrunL[M,A](next)(cw))
        case PAborting(Some(sp),cp) => M.bind(cp)(_ => myrunL[M,A](next)(sp.out))
      }
    }
  }



  // flip should atomawashi
//  case class Flip2[P[_,_,_],A,B[_],C](unFlip2:P[B[_],A,C])
//
////  implicit def fFlip2[P[_,_,_],A1,B1](implicit F: Functor[P[A1,B1,?]]) = new Functor[Flip2[P,A1,B1,?]]{
////    override def map[A, B](fa: Flip2[P, A1, B1, A])(f: A => B): Flip2[P, A1, B1, B] = Flip2(F.map(fa.unFlip2)(f))
////  }
//
//  implicit def bifFlip2[M[_],T](implicit F:Functor[M]) = new Bifunctor[Flip2[EitherT,?,M,?]] {
//    override def bimap[A, B, C, D](fab: Flip2[EitherT, A, M, B])(f: A => C, g: B => D): Flip2[EitherT, C, M, D] = {
//      val ma = fab.unFlip2.run
//      Flip2[EitherT,C,M,D](EitherT(ma.map(a => a.bimap(f,g))))
//    }
//  }



}
