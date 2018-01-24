package test.transformer

import org.scalatest.FunSuite
import contextworkflow._

import scala.language.{higherKinds, implicitConversions, reflectiveCalls}
import scala.util.Try
import scalaz._
//import Scalaz._
import contextworkflow.cwfutil._
import contextworkflow.cwmonad._

import scala.util.DynamicVariable
import scalaz.effect._
import scalaz.std.AllFunctions._
import scalaz.std.list._
import scalaz.syntax.foldable._

class ContextWorkflowTest extends FunSuite{

  //type LOG[A] = WriterT[IO,String,A]
//  implicit val logM = WriterT.writerTMonad[IO,String]
//
//  def log(str: => String, io:IO[Unit] = IO(())):LOG[Unit] = WriterT(io.flatMap(_ => IO((str,()))))

  val RC = ReactiveContext

  val nocheckStrm:Stream[Context] = Continue +: Stream(Continue)

  val dat = new DynamicVariable("")

  def mytest[A](thunk: => A) = dat.withValue(""){thunk}

  def log(str: => String):IO[Unit] = IO{
    dat.value = dat.value + "_" + str
  }
  //implicit val M:Monad[Writer[List[String],?]] = WriterT.writerMonad[List[String]]

  def testP[R]:CWF[R,Unit] = for {
    _ <- (log("n0")) %% (_ => log("f0"))
    _ <- (log("n1")) %% (_ => log("f1"))
    _ <- (log("n2")) %% (_ => log("f2"))
    _ <- (log("n3")) %% (_ => log("f3"))
  } yield ()
  
  test("success"){
    mytest{
      testP.run(RC(Continue))
      assert(dat.value == "_n0_n1_n2_n3")
    }

  }

  test("compensation"){

    mytest{
      testP.run(RC(List(Continue,Continue,Abort)))
      assert(dat.value == "_n0_n1_f1_f0")
    }
  }

  test("abort <> restart"){

    //    def testP[R,C]:CW[R,IO,Fix[CW[R,IO,?,?],C],Unit] = for {
    //      _ <- compensateWith(log("n0"))(_ => log("f0"))
    //      _ <- compensateWith(log("n1")/* >> IO{ctx = Abort}*/)(_ => log("f1"))
    //      _ <- IO{ctx = Abort} %% ()
    //      _ <- compensateWith(log("n2"))(_ => log("f2"))
    //    } yield ()

    val abrt = mytest{
      testP.run(RC(List(Continue,Continue,Abort)))
      //assert(dat.value == "_n0_n1_f1_f0")
      dat.value
    }

    var rest = mytest{
      myrunL[IO,Unit](List(RC(List(Continue,Continue,Restart))))(testP).unsafePerformIO()
      dat.value
      //assert(dat.value == "_n0_n1_f1_f0")
    }
    abrt != rest
  }

  test("A long CW does not lead to StackOverflow"){
    val len = 5000
    def longTest[R] = (1 to len).toList.foldLeftM[CWF[R,?],Unit](())((_,_) => testP[R])

    Try{
      longTest.run(RC(Continue))
    }.isSuccess
  }

  test("suspend test") {
//    def testP[R, C]: CW[R, IO, Fix[CW[R, IO, ?, ?], C], Unit] = for {
//      _ <- compensateWith(log("n0"))(_ => log("f0"))
//      _ <- compensateWith(log("n1"))(_ => log("f1"))
//      _ <- compensateWith(log("n2"))(_ => log("f2"))
//      _ <- compensateWith(log("n3"))(_ => log("f3"))
//    } yield ()

    val rc = RC(List(Continue, Continue, Suspend))
    //val rc = ReactiveContext.fromList(List(Abort))

    val sus = mytest {
      val sus = testP.run(rc) match {
        case -\/(p) => p
        case _ => None
      }

      assert(sus.isDefined)
      assert(dat.value == "_n0_n1")
      sus
    }

    val sp = sus.get.out

    mytest{
      val s = myrun[IO,Unit](RC(Abort))(sp).unsafePerformIO() match {
        case -\/(p) => p
        case _ => None
      }

      assert(s.isEmpty)
      assert(dat.value == "_f1_f0")
    }

    mytest{
      val s = myrun[IO,Unit](RC(Continue))(sp).unsafePerformIO() match {
        case -\/(p) => p
        case _ => None
      }

      assert(s.isEmpty)
      assert(dat.value == "_n2_n3")
    }

    mytest{
      val s = myrun[IO,Unit](RC(List(Continue,Suspend)))(sp).unsafePerformIO() match {
        case -\/(p) => p
        case _ => None
      }

      assert(s.isDefined)
      assert(dat.value == "_n2")
    }
  }

  test("adding workflow to the suspend one") {
    def testP[R]: CWF[R, String] = for {
      _ <- compensateWith(log("n0"))(_ => log("f0"))
      _ <- compensateWith(log("n1"))(_ => log("f1"))
      _ <- compensateWith(log("n2"))(_ => log("f2"))
      _ <- compensateWith(log("n3"))(_ => log("f3"))
    } yield "end?"

    val rc = RC(List(Continue, Continue, Suspend))

    val sus = mytest {
      val sus = testP.run(rc) match {
        case -\/(p) => Some(p)
        case _ => None
      }

//      assert(sus.isDefined)
//      assert(dat.value == "_n0_n1")
      sus
    }

//    def sp[R] = for{
//      str <- sus.get.out.asInstanceOf[CW[R,IO,SUS[R,R,IO],String]]
//      _ <- log(str) %% (_ => log("end?c"))
//      _ <- log("end") %% (_ => log("endc"))
//    } yield ()
//
//    mytest{
//      val s = myrun(RC(Continue))(sp[Unit]).unsafePerformIO()._2 match {
//        case -\/(p) => Some(p)
//        case _ => None
//      }
//      assert(s.isEmpty)
//      assert(dat.value == "_n2_n3_end?_end")
//    }
//
//    mytest{
//      val s = myrun(RC(List(Continue,Continue,Continue,Abort)))(sp[Unit]).unsafePerformIO()._2 match {
//        case -\/(p) => Some(p)
//        case _ => None
//      }
//      assert(s.isEmpty)
//      assert(dat.value == "_n2_n3_end?_end?c_f3_f2_f1_f0")
//    }
  }

  test("higher-order workflow"){
    def testPP[R]: CWF[R,CWF[R,Unit]]
//    :CW[R,IO,SUS[R,R,IO],CW[R,IO,Fix[CW[R,IO,?,?],R],Boolean]]
    =
      for{
        _ <- testP
        //_ <- IO(testP[R,R]) %% (_ => IO(testP[R,R]))
      } yield testP[R]

    mytest{
      myrun[IO,Unit](RC(Continue))(testPP[Unit].join).unsafePerformIO()
      assert(dat.value == "_n0_n1_n2_n3_n0_n1_n2_n3")
    }

    mytest{
      myrun[IO,Unit](
        RC(List(Continue,Continue,Continue,Continue,Continue,Abort)))(testPP[Unit].join).unsafePerformIO()
      assert(dat.value == "_n0_n1_n2_n3_n0_f0_f3_f2_f1_f0")
    }
  }

  
  test("atomic"){

    var ctx:Context = Continue

    def testP3[R]:CWF[R,Unit] = for {
      _ <- log("n0") %% (_ => log("f0"))
      a <- atomicL[Unit,IO,SUS[R],Unit]{
        for{
          _ <- log("an1") %% (_ => log("af1"))
          _ <- IO{ctx = Abort} %% ()
          _ <- log("an2") %% (_ => log("af2"))
        } yield ()
      }
      _ <- log("n1") %% (_ => log("f1"))
    } yield ()


    mytest{
      val rc = ReactiveContext.fromFun(() => ctx)
      myrun[IO,Unit](rc)(testP3).unsafePerformIO()
      assert(dat.value == "_n0_an1_an2_af2_af1_f0")
    }

    mytest{
      val rc = ReactiveContext.fromFun(() => ctx)
      ctx = Continue
      myrun[IO,Unit](rc)(atomicL(testP3)).unsafePerformIO()
      assert(dat.value == "_n0_an1_an2_n1")
    }
  }

  test("nonatomic"){

    var ctx:Context = Continue

    def testAP[R]:CWF[R,Unit] = for {
      _ <- log("n0") %% (_ => log("f0"))
      a <- atomicL[Unit,IO,SUS[R],Unit]{
        for{
          _ <- log("an1") %% (_ => log("af1"))
          _ <- nonatomicL[Unit,IO,SUS[R],Unit](testP)
          _ <- log("an2") %% (_ => log("af2"))
        } yield ()
      }
      _ <- log("n1") %% (_ => log("f1"))
    } yield ()

    def testAP2[R] = {
      atomicL{ for{
        _ <- log("ap0") %% (_ => log("fap0"))
        _ <- nonatomicL(testAP[R])
        _ <- log("ap1") %% (_ => log("fap1"))
      } yield ()
      }
    }


    mytest{
      val rc = RC(List(Continue,Continue,Continue,Abort))
      myrun[IO,Unit](rc)(testAP).unsafePerformIO()
      assert(dat.value == "_n0_an1_n0_n1_f1_f0_af1_f0")
    }

    mytest{
      val rc = RC(List(Continue,Continue,Continue,Abort))
      myrun[IO,Unit](rc)(testAP2).unsafePerformIO()
      assert(dat.value == "_ap0_n0_an1_n0_n1_f1_f0_af1_f0_fap0")
    }
  }

  test("subw"){
    def testP4[R]:CWF[R,Unit] = for {
      _ <- log("S") %% (_ => log("F1"))
      _ <- log("C1") %% (_ => log("F2"))
      a <- subL[Unit,IO,SUS[R],Unit](for{
        _ <- log("C2") %% (_ => log("F3"))
        _ <- log("C3") %% (_ => log("F4"))
      } yield ())
      _ <- log("C4") %% (_ => log("F5"))
      _ <- log("C5") %% (_ => log("F6"))
    } yield ()


    val testStrm = Continue +: Continue +: Continue +: Continue +: Continue +: Abort +: nocheckStrm
    val testStrm_ = Continue +: Continue +: Continue +: Restart +: nocheckStrm

    mytest{
      myrunL(List(RC(testStrm),RC(testStrm_)))(testP4[Unit]).unsafePerformIO()
      assert(dat.value == "_S_C1_C2_C3_C4_F5_F2_F1")
    }
  }

  implicit val M = IO.ioMonad

  test("subw and partial (HS:testP4)"){
    def testP4[R]:CWF[R,Unit]
    = for {
      _ <- log("S") %% (_ => log("F1"))
      _ <- cp[R]
      _ <- log("C1") %% (_ => log("F2"))
      a <- subL(for{
        _ <- log("C2") %% (_ => log("F3"))
        _ <- cp[R]
        _ <- log("C3") %% (_ => log("F4"))
        _ <- log("C4") %% (_ => log("F5"))
      } yield ())
      _ <- compensateWith(log("C5"))(_ => log("F6"))
      _ <- compensateWith(log("C6"))(_ => log("F7"))
    } yield ()


    val testStrm = Continue +: Continue +: Continue +: Continue +: Restart +: nocheckStrm
    val testStrm_ = Continue +: Continue +: Continue +: Restart +: nocheckStrm

    mytest{
      myrunL(List(RC(testStrm),RC(testStrm_)))(testP4[Unit]).unsafePerformIO()
      assert(dat.value == "_S_C1_C2_C3_F4_C3_C4_C5_F6_F2_C1_C2_C3_C4_C5_C6")
    }
  }

  test("partial restart after suspend (HS:testP5)"){
    def testP5[R,C]//:CW[C,R,IO,Fix[CW[C,R,IO,?,?],C],Boolean]
    = for {
      _ <- log("S") %% (_ => log("F1"))
      _ <- cp[R]
      _ <- log("P") %% (_ => log("toP"))
      _ <- log("RCV") %% (_ => log("COMP"))
      _ <- log("END") %% (_ => log("Never"))
    } yield ()

    val testStrm = Continue +: Continue +: Suspend +: nocheckStrm
    val testStrm_ = Continue +: Restart +: nocheckStrm

    val s = mytest{
      val s = myrun[IO,Unit](RC(testStrm))(testP5).unsafePerformIO() match {
        case -\/(p) => p
        case _ => None
      }
      assert(dat.value == "_S_P")
      //assert(dat.value == "_S_P_RCV_COMP_toP_P_RCV_END")
      s
    }

    mytest{
      myrunL[IO,Unit](List(RC(testStrm_)))(s.get.out).unsafePerformIO()
      assert(dat.value == "_RCV_COMP_toP_P_RCV_END")
    }
  }

  test("atomic, partial and suspend (HS:testP5)"){
    def testP[R]//:CW[C,R,IO,Fix[CW[C,R,IO,?,?],C],Boolean]
    = for {
      _ <- log("C1") %% (_ => log("F1"))
      _ <- atomicL[Unit,IO,SUS[R],Unit]{ for {
        _ <- log("C2") %% (_ => log("F2"))
        _ <- cp[R]
        _ <- log("C3") %% (_ => log("F3"))
        _ <- log("C4") %% (_ => log("F4"))
      } yield ()}
      _ <- log("C5") %% (_ => log("F5"))
      _ <- log("C6") %% (_ => log("F6"))
    } yield ()

    val testStrm1 = Continue +:  Suspend +: nocheckStrm
    val testStrm2 = Continue +: Restart +: nocheckStrm
    val testStrm3 = Restart +: nocheckStrm // +: Restart +: nocheckStrm

    val s = mytest{
      val s = myrun[IO,Unit](RC(testStrm1))(testP).unsafePerformIO() match {
        case -\/(p) => p
        case _ => None
      }
      assert(dat.value == "_C1_C2_C3_C4")
      //assert(dat.value == "_S_P_RCV_COMP_toP_P_RCV_END")
      s
    }

    mytest{
      myrunL[IO,Unit](List(RC(testStrm2),RC(testStrm3)))(s.get.out).unsafePerformIO()
      assert(dat.value == "_C5_F5_F4_F3_C3_C4_F4_F3_C3_C4_C5_C6")
    }
  }


  test("partial, sub and programmable workflow"){
    var step = 0

    def testSP1[R,C]//:CW[C,R,IO,Fix[CW[C,R,IO,?,?],C],Boolean]
    = for {
      _ <- log("n0") %% (_ => log("f0"))
      _ <- cp[R]
      _ <- log("n1") %% (_ => log("f1"))
      a <- subL[Unit,IO,SUS[R],String](for{
        _ <- log("sn0") %% (_ => log("sf0"))
        _ <- cp[R]
        _ <- log("sn1") %% (_ => log("sf1"))
        a <- subL[Unit,IO,SUS[R],String](for{
          _ <- log("ssn0") %% (_ => log("ssf0"))
          _ <- cp[R]
          _ <- log("ssn1") %% (_ => log("ssf1"))
          _ <- whenM[CWF[R,?],Unit](step == 0){for{
            _ <- IO{step += 1} %% ()
            _ <- throwTError[IO,SUS[R],Unit](Restart)
          } yield ()}
          _ <- compensateWith(log("ssn2"))(_ => log("ssf2"))
        } yield "ssprcomp") %% (a => log(a))
        _ <- log("sn2") %% (_ => log("sf2"))
        _ <- whenM[CWF[R,?],Unit](step == 1){for{
          _ <- IO{step += 1} %% ()
          _ <- throwTError[IO,SUS[R],Unit](Restart)
        } yield ()}
        _ <- log("sn3") %% (_ => log("sf3"))
      } yield "sprcomp") %% (a => log(a))
      _ <- compensateWith(log("n2"))(_ => log("f2"))
      _ <- compensateWith(log("n3"))(_ => log("f3"))
      _ <- whenM[CWF[R,?],Unit](step == 2){for{
        _ <- IO{step += 1} %% ()
        _ <- throwTError[IO,SUS[R],Unit](Restart)
      } yield ()}
      _ <- compensateWith(log("n4"))(_ => log("f4"))
    } yield ()

    mytest{
      val ret = myrunL[IO,Unit](List(RC(Continue)))(testSP1).unsafePerformIO()
      //assert(ret == )
      assert(dat.value ==
        "_n0_n1_sn0_sn1_ssn0_ssn1_ssf1_ssn1_ssn2" +
          "_sn2_sf2_ssprcomp_sf1_sn1_ssn0_ssn1_ssn2" +
          "_sn2_sn3_n2_n3_f3_f2_sprcomp_f1_n1" +
          "_sn0_sn1_ssn0_ssn1_ssn2_sn2_sn3_n2_n3_n4")
    }
  }
}
