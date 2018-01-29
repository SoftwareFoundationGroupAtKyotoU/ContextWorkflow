package cw_examples

import scala.swing._
import java.awt.Color
import de.sciss.swingplus.OverlayPanel
import scala.swing.event.ButtonClicked
import contextworkflow._
import cwutil._
import scalaz._
import rescala._
import scala.collection.mutable

object mazegui extends SimpleSwingApplication {
  import mazes._

  val maze = cw_examples.mazes.getMaze1
  val m = 6
  val n = 6

  val mazepane = new GridPanel(m, n) {
    preferredSize = new Dimension(500, 500)

    var nodehm = new mutable.HashMap[(Int,Int),BoxPanel]

    for (j <- 0 to n - 1; i <- 0 to m - 1) {
      val cell = maze.find(_.point == (i, j)) match {
        case None => Color.black
        case Some(nd) => if(nd.hasCP) Color.green else if(nd.visited) Color.lightGray else Color.white
      }
      val pane = new BoxPanel(Orientation.NoOrientation) {
        background = cell

//        override def paintComponent(g: Graphics2D) {
//          super.paintComponent(g)
//          maze.foreach{nd =>
//            val pane = nodehm(nd.point)
//            pane.background =
//              if(nd.hasCP) Color.green
//              else if(nd.visited) Color.lightGray
//              else Color.white
//          }
//        }
      }
      nodehm += (((i,j),pane))
      contents += pane
    }

    override def paintComponent(g: Graphics2D) {
      super.paintComponent(g)
      maze.foreach{nd =>
        val pane = nodehm(nd.point)
        pane.background =
          if(nd.hasCP) Color.green
          else if(nd.visited) Color.lightGray
          else Color.white
      }
    }
  }

  val startNodePane = pointToPos((0,0),(m,n))
  val xpos = Var(startNodePane.x)
  val ypos = Var(startNodePane.y)

  val robotpane = new Panel() {
    //preferredSize = new Dimension(500, 500)
    val Size = 50
    val adjx = 20 //mazepane.nodehm.get((0,0)).get.size.width / 2
    val adjy = 20 //mazepane.nodehm.get((0,0)).get.size.height / 2
    opaque = false
    //val position = new Point(100, 100)

    override def paintComponent(g: Graphics2D) {
      super.paintComponent(g)
      g.setColor(Color.BLUE)
      g.fillOval(xpos.now + adjx, ypos.now + adjy, Size, Size)
    }
  }

  var ctx:Var[Context] = Var(Continue)
  var suspendedCW:Option[CW[Unit]] = None
  var restartflag = false

  //lazy val application = this
  def top = new MainFrame {

    val search = new OverlayPanel

    val lp = search.peer
    lp.setPreferredSize(new Dimension(500, 500))

    //robotpane.opaque = false

    val button = new Button("hoge")
    button.background = Color.blue

    //lp.add(button.peer,"3")
    lp.add(robotpane.peer,"2")
    lp.add(mazepane.peer,"1")

    contents = new BoxPanel(Orientation.Vertical) {
      contents += new GridPanel(3, 2) {
        contents += new Button("Abort") {
          reactions += {
            case e: ButtonClicked => println("Abort");ctx.update(Abort)
          }
        }
        contents += new Button("Suspend") {
          reactions += {
            case e: ButtonClicked => ctx.update(Suspend)}
        }
        contents += new Button("PAbort") {
          reactions += {
            case e: ButtonClicked => ctx.update(PAbort)}
        }
        contents += new Button("Restart") {
          reactions += {
            case e: ButtonClicked =>
              restartflag = true
//              suspendedCW match{
//                case Some(p) => ctx = Var(Continue);p.exec(RC(ctx))
//                case None => ()
//              }
          }
        }
//        contents += new Button("flush") {
//          reactions += {
//            case e: ButtonClicked =>
//              restartflag = false
//              initMaze(maze)
//          }
//        }
      }
      contents += search
    }
  }

  val tick = Evt[Unit]
  tick += { _: Unit =>
    robotpane.repaint()
    //mazepane.repaint()
    //mazepane.nodehm.values.foreach(_.repaint())

  }

  def pointToPos(p: (Int,Int), max:(Int,Int)):Point = {
    val pane = mazepane.nodehm(p._1,p._2)
    pane.location
  }

  def moveGUI(n: Node, info: String = ""):Unit = {
    val num = 20
    val frompos = new Point(xpos.now, ypos.now)
    val topos = mazepane.nodehm(n.point).location
    val dx = (topos.x - frompos.x) / num
    val dy = (topos.y - frompos.y) / num
    for (i <- 1 to num) {
      xpos.update(frompos.x + dx * i)
      ypos.update(frompos.y + dy * i)
      //println(xpos.now,ypos.now)
      Swing onEDTWait {
        tick.fire()
      }
      Thread sleep 30
    }
    println(info + "[move to " + n.point + "]")
  }

  def visit(n: mazes.Node, maze: Set[mazes.Node]):CW[Unit] = lift {
    unlift(visited(n) %% (_ => unknown(n)))
    if(n.hasCP) unlift(cp)
    unlift{foldCW(neighbors(n, maze))(())((_, neighbor) =>
      if(!isVisited(neighbor))
        sub{ lift{
          unlift(moveGUI(neighbor) /+ (_ => moveGUI(n, "comp:")))
          //unlift(println(ctx.now) /+ ())
          unlift(visit(neighbor, maze))
          unlift(moveGUI(n, "back:") /+ ())
        } }
      else () /+ ()
    ) }
  }

  override def main(args: Array[String]): Unit = {
    super.main(args)

    Thread sleep 500

    val start0: Node = maze.find(_.point == (0,0)).get
    visit(start0,maze).exec(RC(ctx)) match {
      case -\/(None) => suspendedCW = Some(visit(start0,maze))
      case -\/(Some(p)) => suspendedCW = Some(p)
      case \/-(r) => println("Succeed!")
    }

    while (true) {
      //Swing onEDTWait {tick.fire()}
      Thread sleep 200
      if(restartflag){
        restartflag = false
        suspendedCW match{
          case Some(p) => ctx = Var(Continue);
            p.exec(RC(ctx)) match {
              case -\/(None) => suspendedCW = Some(visit(start0,maze))
              case -\/(Some(p)) => suspendedCW = Some(p)
              case \/-(r) => println("Succeed!")
            }
          case None => ()
        }
      }
    }
  }
}
