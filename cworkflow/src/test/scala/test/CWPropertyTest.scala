package test.transformer

//import org.scalatest.junit.JUnitSuite
//import org.scalatest.prop.{Checkers, GeneratorDrivenPropertyChecks}
//import Checkers._
//import org.scalacheck.Prop._
//import org.scalacheck.Arbitrary._
//import org.scalatest.prop.Configuration.PropertyCheckConfig
import org.scalacheck._
import org.scalacheck.Test.Parameters

import transformer._
import cwmonad._
import cwutil._

import scala.language.implicitConversions
import scala.language.reflectiveCalls
import scala.language.higherKinds
import scalaz._

//import org.scalatest.{Matchers, WordSpec}

import effect._
//import scalaz.syntax.bind._
import scalaz.std.list._
import scalaz.syntax.foldable._
//import scalaz.std.string._
//import scalaz.std.AllFunctions._
import scala.util.DynamicVariable


object cwfnspecutil{

  /** signal generator */

  def genSig(): Gen[List[Context]] = genSig(Continue,Abort,Restart,Suspend)

  def genSig(t0: Context*): Gen[List[Context]] = {
    def genRC(i:Int):Gen[List[Context]] = for {
      l <- Gen.listOfN(i, Continue)
      ae <- Gen.oneOf(t0)
    } yield (ae :: l).reverse

    Gen.sized(sz => for{
      i <-  Gen.oneOf(1 to sz)
      rc <- genRC(i)
    } yield rc)
  }

  implicit val arbSig: Arbitrary[List[Context]] = Arbitrary{
    genSig()
    //Gen.sized(sz => Gen.listOfN(sz, Gen.frequency(10 -> Continue, 1 -> Abort, 1 -> Restart, 1 -> Suspend))
  }

  implicit val arbListSig: Arbitrary[List[List[Context]]] = Arbitrary{Gen.sized(Gen.listOfN(_,arbSig.arbitrary))}


  /** config for frequency of generator */

  // (leaf freq, node freq)
  val initLNFreq = (1,200)
  val leftLNFreq = (10,1)
  val rightLNFreq = (1,10)

  val pwcpFreq = (5,1)


  /** command list that represents workflows */

  sealed trait TestCmd
  object PW extends TestCmd{
    override def toString: String = "PW"
  }  // P_k = a_k/c_k
//  object SubB extends TestCmd  // sub begin
//  object SubE extends TestCmd // sub end
  object CP extends TestCmd{
    override def toString: String = "CP"
  }  // check point


  /** Tree structure to represent sub-workflow */

  sealed trait Tree[T]{
    def size:Int

    def toList:List[T]

    def isNode:Boolean

    def hasLeftNode:Boolean

    def leftNodeNum:Int

    def toString: String
  }
  case class Node[T](left: Tree[T], right: Tree[T]) extends Tree[T]{
    def size = left.size + right.size

    def toList:List[T] = left.toList ++ right.toList

    def isNode:Boolean = true

    def hasLeftNode:Boolean = left.isNode || right.hasLeftNode

    def leftNodeNum:Int = if(left.isNode) 1 + left.leftNodeNum else 0

    override def toString: String = left match {
      case Leaf(_) => left.toString + "," + right.toString
      case Node(_,_) => "(" + left.toString + ")," + right.toString
    }
  }
  case class Leaf[T](x: T) extends Tree[T]{
    def size = 1

    def isNode:Boolean = false

    def hasLeftNode:Boolean = false

    def leftNodeNum:Int = 0

    def toList:List[T] = List(x)

    override def toString: String = x.toString
  }

  def genTree[T](genT: Gen[T], lnfreq:(Int,Int) = initLNFreq): Gen[Tree[T]] = Gen.lzy {
    Gen.sized{size =>
      if (size <= 1) genLeaf(genT)
      else Gen.frequency(lnfreq._1 -> genLeaf(genT), lnfreq._2 -> genNode(genT))
    }
  }

  def genLeaf[T](genT: Gen[T]): Gen[Leaf[T]] =
    genT.map(Leaf(_))

  def genNode[T](genT: Gen[T]): Gen[Node[T]] =
    for {
      //s <- Gen.choose(0, size)
      left <- Gen.sized(h => Gen.resize(h/2,genTree(genT,leftLNFreq)))  // no frequent sub
      g = Gen.sized(size => Gen.resize(size - left.size, genTree(genT,(rightLNFreq._1, rightLNFreq._2)))) // longer and longer
      right <- g
    } yield Node(left,right)

  def genNoLeftNodeTree[T](genT: Gen[T]): Gen[Tree[T]] = Gen.sized { size =>
    if (size == 1) genLeaf(genT) else Gen.resize(size - 1, for{
      l <- genLeaf(genT)
      r <- genNoLeftNodeTree(genT)
    } yield Node(l,r))
  }

  type Term = Tree[TestCmd]

  /* effectful primitive workflow */

  val effl = new DynamicVariable(List[Int]())

  def eff[R](i:Int, io: IO[Unit] = IO(())):CW[Unit] = IO{effl.value = i :: effl.value}.flatMap(_ => io) %% (_ => IO(effl.value = -i :: effl.value)) //{_ => IO(effl.value = effl.value.filterNot(_ == i))}

  val effList = (1 to 1000).map(eff(_))

  def mytest[A](thunk: => A) = effl.withValue(Nil){thunk}

  val SUBOFFSET = -5000000

  implicit def tct2cwf(tct: Tree[TestCmd]):CW[Unit] = {
    var effcount = 1
    var subecount = SUBOFFSET

    def tc2cwf(tc: TestCmd):CW[Unit] = tc match {
      case PW => {
        val cw = eff(effcount, IO{effcount += 1})
        cw}
      case CP => cp
    }

    def aux(t: Tree[TestCmd]):CW[Unit] = t match{
      case Leaf(cmd) => tc2cwf(cmd)
      case Node(Leaf(cmd),n) => for{
        _ <- tc2cwf(cmd)
        _ <- aux(n)
      } yield ()
      case Node(n1:Node[TestCmd],n2) => for{
        _ <- sub(aux(n1)) /+ (_ => {effl.value = subecount :: effl.value; subecount -= 1})
        _ <- aux(n2)
      } yield ()
    }

    aux(tct)
  }

  /* generator */

  val genTestCmd:Gen[TestCmd] = Gen.frequency(pwcpFreq._1 -> PW, pwcpFreq._2 -> CP)

  implicit val arbTestCmdTree: Arbitrary[Tree[TestCmd]] = Arbitrary{genTree(genTestCmd)}

  val genNoSubCmdTree: Gen[Tree[TestCmd]] = genNoLeftNodeTree(genTestCmd)

  implicit val arbCWF: Arbitrary[CW[Unit]] = Arbitrary{genTree(genTestCmd).map(tct2cwf)}


//  val sv = new DynamicVariable(0)
//  def testP[R](max:Int) = for{
//    _ <- (1 to 100).toList.foldLeftM[CWFN,Unit](()){
//      (_,n) => IO(sv.value = sv.value + n) %% (_ => IO(sv.value = sv.value - n))
//    }
//  } yield ()
}

//class CWPropertyTest extends WordSpec with Matchers with GeneratorDrivenPropertyChecks{
object CWPropertyTest extends Properties("PWorkflow"){
  import cwfnspecutil._

  //implicit override val generatorDrivenConfig = PropertyCheckConfig(minSize = 10, maxSize = 20, minSuccessful = 200)

  import org.scalacheck.Prop.{forAll,BooleanOperators}
  import org.scalacheck.Prop._

  //override def overrideParameters(p: Parameters) =  p.withMinSuccessfulTests(200).withMinSize(10).withMaxSize(20)


  property("conj1") = forAllNoShrink(arbTestCmdTree.arbitrary) { (tr: Tree[TestCmd]) =>
    forAll(Gen.resize(tr.size, genSig(Continue))) {s =>
      mytest {
        //println(s)
        val ss = if (s == Nil) List(Continue) else s
        val r = tr.runBMReturnsContext(RC(ss))
        val max = tr.toList.filter(_ == PW).length
        val effects = effl.value.reverse
        val neff = effects.filter(_ > 0)
        val ceff = effects.filter(_ < 0)


        // Don't use effl (DynamicVariable) in the implication or to construct properties directly

        // conjecture 1
        val p1 = ((r._1.isRight) ==> max > 0) ==> {
          (!effects.isEmpty && ceff.isEmpty) &&
            (effects == (1 to max).toList)
        }

        // conjecture 2
        //val p2 = (!tr.hasLeftNode && (r._2 == Abort)) ==> (neff.map(a => -a).reverse == ceff)

        p1
      }
    }
  }

  property("conj2") = forAll { (tr: Tree[TestCmd]) =>
    forAll(Gen.resize(tr.size, genSig(Abort))) { s =>
      mytest {
//        println(s)
//        println(tr)
        val r = tr.runBMReturnsContext(RC(s))
//        val max = tr.toList.filter(_ == PW).length
        val effects = effl.value.reverse
        val neff = effects.filter(_ > 0)
        val ceff = effects.filter(_ < 0)


        // conjecture 2
        val p2 = (!tr.hasLeftNode && (r._2 == Abort)) ==> (neff.map(a => -a).reverse == ceff)

        p2
      }
    }
  }

  property("conj3") = forAll { (tr: Tree[TestCmd]) =>
    forAll(Gen.resize(tr.size, genSig(Suspend))) { s =>
      mytest {
        val r = tr.runBMReturnsContext(RC(s))
        val max = tr.toList.filter(_ == PW).length
        val effects = effl.value.reverse
        val neff = effects.filter(_ > 0)
        val ceff = effects.filter(_ < 0)

        // conjecture 2
        mytest{
          val p3 = r._1 match {
            case -\/(Some(p)) => {
              p.runBMReturnsContext(RC(Continue))
              val eff2 = effl.value.reverse
              val eff0 = mytest{
                tr.runBMReturnsContext(RC(Continue))
                effl.value.reverse
              }
              //println(eff0, effects, eff2)
              eff0 == effects ++ eff2
            }
            case _ => false
          }
          (r._2 == Suspend) ==> p3
        }
      }
    }
  }

  property("conj4") = forAll { (tr: Tree[TestCmd]) =>
    Prop.propBoolean(!tr.hasLeftNode) ==> forAll(Gen.resize(tr.size, genSig(Restart))) { s =>
      mytest {
        val cw = tct2cwf(tr)
        val r = cw.runBMReturnsContext(RC(s))
        val max = tr.toList.filter(_ == PW).length
        val eff1 = effl.value.reverse
        val neff1 = eff1.filter(_ > 0)
        val ceff1 = eff1.filter(_ < 0)

        // conjecture 2

        val prop = r._1 match {
          case -\/(Some(p)) => {

            val p1 = neff1.indexOfSlice(ceff1.map(Math.abs).sorted) >= 0

            val p2 = mytest {
              p.runBMReturnsContext(RC(Continue))
              val eff2 = (eff1 ++ effl.value).sorted // 1 ... i , -i, ..., -k,k,...,len is sorted
              eff2.indexOfSlice(1 to eff2.max) >= 0 && eff2.toSet.size == eff2.length
            }

            val p3 = mytest {
              p.runBMReturnsContext(RC(Abort))
              (neff1.sorted == ceff1.map(Math.abs).sorted) || effl.value.contains(-1)
            }

            Some(p1 && p2 && p3)
          }
          case _ => None
        }
          //((r._2 == Restart) ==> (r._1.toEither.left.get.isDefined)) ==> p4
        (r._2 == Restart) ==> prop.isDefined ==> prop.get
      }
    }
  }

  def isContinuous(l:List[Int]) = l.isEmpty || l.sorted == (l.min to l.max).toList
  def withoutProComp(l:List[Int]) = l.filter(_ > SUBOFFSET)

  property("conj5 & 6") = forAll { (tr: Tree[TestCmd]) =>  //including conj5
    forAll(Gen.resize(tr.size, genSig(Restart))) { s =>
      mytest {
        val cw = tct2cwf(tr)
        val r = cw.runBMReturnsContext(RC(s))
        val max = tr.toList.filter(_ == PW).length
        val eff1 = effl.value.reverse
        val neff1 = eff1.filter(_ > 0)
        val ceff1 = eff1.filter(_ < 0)

        // conjecture 2

        val prop = r._1 match {
          case -\/(Some(p)) => {
//            println("**********")
//            println(s)
//            println(tr)
//            println(eff1)

            val p1 = isContinuous(withoutProComp(ceff1)) || ceff1.exists(_ <= SUBOFFSET)

            val p2 = mytest {
              val r2 = p.runBMReturnsContext(RC(List(Continue,Restart)))
              val eff2 = effl.value
//              println("p2:",eff2, r2)
              !eff2.exists(_ < 0)  ||  // stopping at another future CP
               (eff2.max == - eff2.min) ||  // return to this CP
                r2._2 == Continue ||  // commits
                eff2.min <= SUBOFFSET // has sub-workflow
            }
            //val p3:Prop
            val p3 = mytest {
              val r3 = p.runBMReturnsContext(RC(List(Continue,Continue,Continue,Continue,Restart)))
              val eff3 = effl.value
//              println("p3:" + eff3 + r3)
              val a = r3._1 match {
                case -\/(Some(_)) =>
                  !eff3.exists(_ < 0) || // stopping at another future CP
                    (eff3.max == - eff3.min) ||
                    (eff3.min <= SUBOFFSET)
                case _ => true
              }
              //(a.isDefined ==> a.get)
              a
            }
            // println(p1, p2, p3)
            Some(p1 && p2 && p3)
          }
          case _ => None
        }
        //((r._2 == Restart) ==> (r._1.toEither.left.get.isDefined)) ==> p4
        (r._2 == Restart) ==> prop.isDefined ==> prop.get
      }
    }
  }


}
