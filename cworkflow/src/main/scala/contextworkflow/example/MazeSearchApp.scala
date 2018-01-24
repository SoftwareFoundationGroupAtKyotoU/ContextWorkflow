package contextworkflow.example

import contextworkflow._
import rescala._
import scala.language.implicitConversions

import scalaz._
import scalaz.effect._
import Scalaz._


/**
  * Created by hinoue on 2017/08/02.
  */

object MazeSearchMlessApp extends App {
  import cwutil._
  import cwmless._
  import mazes._
  import scala.language.reflectiveCalls

  def visit(n: Node, maze: Set[Node]):CW[Unit] = lift {
    unlift(visited(n) %% (_ => unknown(n)))
    if(n.hasCP) unlift(cp)
    unlift{neighbors(n, maze).foldLeftM[CW,Unit](())((_, neighbor) =>
      if(!isVisited(neighbor))
        sub{ lift{
          unlift(move(neighbor) %% (_ => move(n, "comp:")))
          unlift(visit(neighbor, maze))
          unlift(move(n, "back:") %% ())
        } }
      else () /+ ()
//      wf {
//        if (!isVisited(neighbor))
//          unlift(sub {
//            wf {
//              unlift(move(neighbor) %% (_ => move(n, "comp:")))
//              unlift(visit(neighbor, maze))
//              unlift(move(n, "back:") %% ())
//            }
//          })
//      }
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
  def to = timeout(7,Restart)()

  val p1 = visit(start0, maze0).exec(RC(to)) match {
    case -\/(Some(p)) => p
  }
  println("***** Charging *****")
  val p2 = p1.exec(RC(to)) match {
    case -\/(Some(p)) => p
  }
  println("***** Charging *****")
  p2.exec(RC(to))

  initMaze(maze0)

//  def search(maze: Set[Node], max: Int):Unit = {
//    val startNode: Node = maze.find(_.point == (0,0)).orElse(Some(maze.head)).get
//    println("start from:" + startNode)
//    val to = timeout(max,Abort)()
//
//    visit(startNode, maze).runBM(ReactiveContext.fromSignal(to))
//    val visitedNodes = maze.filter(_.visited).map(_.point)
//    println("visited:" + visitedNodes)
//  }
//
//  val maze0 = getMaze0
//  val maze1 = getMaze1
//
//  search(maze0, 10)
//  search(maze0, 10)
//  search(maze0, 10)

}
