package cw_examples

import contextworkflow._
import cw_examples.mazes.{Node => _}

import scala.language.implicitConversions
import scalaz.Scalaz._
import scalaz._


/**
  * Created by hinoue on 2017/08/02.
  */

object MazeSearchApp extends App {
  import cwutil._
  import mazes._

  import scala.language.reflectiveCalls

  def visit(n: Node, maze: Set[Node]):CW[Unit] = lift {
    unlift(visited(n) /+ (_ => unknown(n)))
    if(n.hasCP) unlift(cp)
    unlift{neighbors(n, maze).foldLeftM[CW,Unit](())((_, neighbor) =>
      if(!isVisited(neighbor))
        sub{ lift{
          unlift(move(neighbor) /+ (_ => move(n, "comp:")))
          unlift(visit(neighbor, maze))
          unlift(move(n, "back:") /+ ())
        } }
      else () /+ ()
    ) }
  }

  def getMaze:Set[Node] = (Set(
    (0, 0), (1, 0), (2, 0), (3, 0),
    (0, 1), LLLLLL, (2, 1), LLLLLL,
    (0, 2), LLLLLL, (2, 2), LLLLLL,
    (0, 3), (1, 3), (2, 3), (3, 3)
  ) - LLLLLL).map(p => Node(p)).map { n =>
    n.point match {
      case (2, 3) => n.copy(hasCP = true)
      case _ => n
    }
  }

  val c = Continue
  val maze0 = getMaze
  val start0: Node = maze0.find(_.point == (0,0)).get
  def to = timeout(7,PAbort)()

  val p1 = visit(start0, maze0).exec(RC(to)) match {
    case -\/(Some(p)) => p
  }
  println("***** Charging *****")
  p1.exec()
//  val p2 = p1.exec(RC(to)) match {
//    case -\/(Some(p)) => p
//  }
//  println("***** Charging *****")
//  p2.exec(RC(to))

//  initMaze(maze0)

}
