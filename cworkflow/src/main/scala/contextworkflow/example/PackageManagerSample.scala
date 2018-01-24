package contextworkflow.example
import contextworkflow._

import scalaz._
import scala.util.{Try,Failure,Success}
import scalaz.effect.IO

object PackageManagerMonadless extends App{
  import cwutil._
  import cwmless._

  case class Pkg(pid: String,version: Double,dep: List[Pkg] = Nil){
    override def toString: String = pid + version.toString
  }

  def dependent(pkg:Pkg) = pkg.dep

  def install(pkg:Pkg):IO[Unit] = IO(println("install:" + pkg))

  def uninstall(pkg:Pkg):IO[Unit] = IO(println("uninstall:" + pkg))

  def replace(n:Pkg,o:Pkg):CW[Unit] =
    install(n) %% (uninstall(n).flatMap(_ => install(o)))

  def newInstall(pkg:Pkg):CW[Unit] =
    install(pkg) %% uninstall(pkg)

//  def upgrade_(opl:List[Pkg],npl:List[Pkg]):CWFN[List[Pkg]] =
//    foldCW(npl)(opl) { (opl1, np) =>
//      lift {
//        val opopt = opl.find(_.pid == np.pid)
//        val instdeps = np.dep.filter(dp =>
//          opl1.exists(op => op.pid == dp.pid && op.version < dp.version) ||
//            !opl1.exists(_.pid == dp.pid))
//        val opl2 = unlift(upgrade_ (opl1, instdeps))
//        val b = unlift {
//          opopt match {
//            case Some(op) => replace(np, op)
//            case None => newInstall(np)
//          }
//        }
//        np :: opl2.filterNot(_.pid == np.pid)
//      }
//    }

  /* completed package is OK */
  def upgrade1(opl:List[Pkg],np:Pkg):CW[List[Pkg]] =
    lift {
      val opopt = opl.find(_.pid == np.pid)
      val instdeps = np.dep.filter(dp =>
        opl.exists(op => op.pid == dp.pid && op.version < dp.version) ||
          !opl.exists(_.pid == dp.pid))

      val opl1 = unlift(foldCW(instdeps)(opl){(opl0, dp) => upgrade1(opl0,dp)})
      unlift {
        opopt match {
          case Some(op) => replace(np, op)
          case None => newInstall(np)
        }
      }
      np :: opl1.filterNot(_.pid == np.pid)
    }

  def upgrade(opl:List[Pkg],npl:List[Pkg]):CW[List[Pkg]] =
    foldCW(npl)(opl) { (opl1, np) => lift{
      unlift(cp);
      unlift(sub(upgrade1(opl1,np)) /+ (_ => println("skip uninstalling completed package:" + np)))
    }}

  val a1 = Pkg("a", 1);  val a2 = Pkg("a", 2)

  val b1 = Pkg("b",1,List(a1));  val b2 = Pkg("b",2,List(a2))

  val c1 = Pkg("c",1,List(a1, b1))
  val c2 = Pkg("c",2,List(a2, b2))

  val d1 = Pkg("d",1,List())

  val e1 = Pkg("e",1,List(d1))

  upgrade(Nil,List(b1)).exec()
  upgrade(List(a1,b1),List(b2)).exec()

  println("******************")
  upgrade(List(a1),List(c2,d1)).exec(RC(List(Continue,Continue,Continue,Abort)))
  println(upgrade(List(a1),List(c2,e1)).exec(RC(List(Continue,Continue,Continue,Abort))))

  println("******************")

  val s = upgrade(List(a1),List(c2,e1)).exec(RC(List(Continue,Continue,Continue,Continue,Restart)))

  println("suspended")

  println(s.toEither.left.get.get.exec(RC(Abort)))
  println(s.toEither.left.get.get.exec(RC(Continue)))

  println("******************")
//
//  val a = runPW(RC(List(Continue,Continue,Restart)))(
//    upgradeSubW[List[Pkg]](List(a1),List(c2,d1))
//  )
//
//  val b = runPW(RC(List(Continue,Continue,Continue,Abort)))(
//    upgradeSubW[List[Pkg]](List(a1),List(c2,d1))
//  )
//
//  val c = runPW(RC(List(Continue,Continue,Continue,Restart)))(
//    upgradeSubW[List[Pkg]](List(a1),List(c2,d1))
//  ).toEither.left.get.get
//
//  myrun(RC(Continue))(c._1,c._2).unsafePerformIO()

}
