package cw_examples

import contextworkflow._
import rescala._

import scala.language.implicitConversions

object mazes{
  case class Node(point:(Int,Int), hasCP:Boolean = false, var visited:Boolean = false)

  //wall
  val LLLLLL = (Int.MinValue, Int.MinValue)

  def getMaze0:Set[Node] = (Set(
    (0, 0), (1, 0), (2, 0), (3, 0),
    (0, 1), LLLLLL, (2, 1), LLLLLL,
    (0, 2), LLLLLL, (2, 2), LLLLLL,
    (0, 3), (1, 3), (2, 3), (3, 3)
  ) - LLLLLL).map(p => Node(p)).map { n =>
    n.point match {
      case (2, 1) => n.copy(hasCP = true)
      case _ => n
    }
  }

  def getMaze1:Set[Node] = (Set(
    (0, 0), (1, 0), (2, 0), (3, 0), (4, 0), (5, 0),
    (0, 1), LLLLLL, (2, 1), LLLLLL, LLLLLL, (5, 1),
    (0, 2), LLLLLL, (2, 2), LLLLLL, (4, 2), LLLLLL,
    (0, 3), (1, 3), (2, 3), (3, 3), (4, 3), (5, 3),
    LLLLLL, (1, 4), LLLLLL, LLLLLL, LLLLLL, (5, 4),
    (0, 5), (1, 5), LLLLLL, (3, 5), (4, 5), (5, 5)
  ) - LLLLLL).map(p => Node(p)).map { n =>
    n.point match {
      case (1, 3)|(3, 0) => n.copy(hasCP = true)
      case _ => n
    }
  }

  def isVisited(n: Node): Boolean = {
    n.visited
  }

  def visited(n: Node): Unit = {
    n.visited = true
  }

  def unknown(n: Node): Unit = {
    n.visited = false
  }

  def neighbors(n: Node, maze: Set[Node]): List[Node] = {
    val candidates = List((1, 0), (0, 1), (-1, 0), (0, -1)).map(p =>
      (n.point._1 + p._1, n.point._2 + p._2)
    )
    maze.filter(n => candidates.exists(_ == n.point)).toList
  }

  // imitate timer
  val moveCount = Var(0)

//  def move(n: Node, info: String = ""):IO[Unit] = for {
//    _ <- IO(moveCount() = moveCount.now + 1)
//    _ <- IO.putStrLn(info + "[move to " + n.point + "]")
//  } yield ()

  def move(n: Node, info: String = ""):Unit = {
    moveCount() = moveCount.now + 1
    println(info + "[move to " + n.point + "]")
  }

  def timeout(threshold: Int, ctx:Context = Abort):() => Signal[Context] = () => {
    val now = moveCount.now
    Signal {
      if (moveCount() > now + threshold) ctx else Continue
    }
  }

  def initMaze(maze: Set[Node]) = maze.foreach(_.visited = false)
}