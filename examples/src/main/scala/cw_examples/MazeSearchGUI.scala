package cw_examples

import scala.swing._
import java.awt.{Color, Font}
import java.net.URL
import javax.imageio.ImageIO

import de.sciss.swingplus.OverlayPanel

import scala.swing.event.ButtonClicked
import contextworkflow._
import cwutil._

import scalaz._
import rescala._

import scala.collection.mutable

object MazeSearchGUI extends SimpleSwingApplication {
  import mazes._

  val maze = cw_examples.mazes.getMaze1
  val m = 6
  val n = 6

  val startnodep = (0,0)
  val robotp = Var(new Point(0,0))
  var robotnodep = startnodep

  val mazepane = new GridPanel(m, n) {
    preferredSize = new Dimension(500, 500)
    var nodehm = new mutable.HashMap[(Int,Int),BoxPanel]

//    private def locToPt(p: (Int,Int), max:(Int,Int)):Point = {
//      val pane = nodehm(p._1,p._2)
//      pane.location
//    }

    for (j <- 0 to n - 1; i <- 0 to m - 1) {
      val cell = maze.find(_.point == (i, j)) match {
        case None => Color.black
        case Some(nd) => if(nd.hasCP) Color.green else if(nd.visited) Color.lightGray else Color.white
      }
      val pane = new BoxPanel(Orientation.NoOrientation) {
        background = cell
      }
      nodehm += (((i,j),pane))
      contents += pane
    }

    val startNodePane = nodehm(startnodep)
    robotp.update(startNodePane.location)

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

  val robotpane = new Panel() {
    //preferredSize = new Dimension(500, 500)
    val Size = 50
    opaque = false

//    val icon = "\uD83E\uDD16"
//    val myfont = new Font("Apple Color Emoji", Font.PLAIN, 20)


    val icon:Option[Image] = try {
      val url = new URL("https://1.bp.blogspot.com/-dJk1u6q9A-k/VGLLP4DQWFI/AAAAAAAAowE/v-fE07TAYY0/s250/robot1_blue.png")
      Some(ImageIO.read(url))
    } catch {
      case e:Exception => None
    }
    //val icon = new String(Character.toChars(0x1F603))
    //val icon = "hogehoge"

    //val position = new Point(100, 100)

    override def paintComponent(g: Graphics2D) {
      super.paintComponent(g)
      val cellsize = mazepane.nodehm.get((0,0)).get.size
      val adjx = (cellsize.width - Size) / 2
      val adjy = (cellsize.height - Size) / 2
      val rp = robotp.now

      g.setColor(Color.BLUE)
      icon match {
        case Some(img) => g.drawImage(img, rp.x + adjx, rp.y + adjy, Size, Size, this.peer)
        case None => g.fillOval(rp.x + adjx, rp.y + adjy, Size, Size)
      }
      //g.setFont(myfont)
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
            case e: ButtonClicked => println("Suspend");ctx.update(Suspend)}
        }
        contents += new Button("PAbort") {
          reactions += {
            case e: ButtonClicked => println("PAbort");ctx.update(PAbort)}
        }
        contents += new Button("Continue") {
          reactions += {
            case e: ButtonClicked =>
              restartflag = true
              println("Continue");
              ctx.update(Continue)
//              suspendedCW match{
//                case Some(p) => ctx = Var(Continue);p.exec(RC(ctx))
//                case None => ()
//              }
          }
        }
        contents += new Button("flush") {
          reactions += {
            case e: ButtonClicked =>
              if(robotnodep == startnodep) {
                restartflag = false
                initMaze(maze)
                tick.fire()
              }
          }
        }
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


  def moveGUI(n: Node, info: String = ""):Unit = {
    val num = 20
    val frompos = mazepane.nodehm(robotnodep).location //new Point(robotp.now.x, robotp.now.y)
    val topos = mazepane.nodehm(n.point).location
    val dx = (topos.x - frompos.x) / num
    val dy = (topos.y - frompos.y) / num
    for (i <- 1 to num) {
      if(i < num) robotp.update(new Point(frompos.x + dx * i, frompos.y + dy * i))
      else {robotnodep = n.point; robotp.update(topos)}
      //println(xpos.now,ypos.now)
      Swing onEDTWait {
        tick.fire()
      }
      Thread sleep 30
    }
    println(info + "[move to " + n.point + "]")
  }

  def visit(n: mazes.Node, maze: Set[mazes.Node]):CW[Unit] = lift {
    unlift(visited(n) /+ (_ => unknown(n)))
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
    seamlessExec(visit(start0,maze),RC(ctx))
    println("He did search!")
//    visit(start0,maze).exec(RC(ctx)) match {
//      case -\/(None) => suspendedCW = Some(visit(start0,maze))
//      case -\/(Some(p)) => suspendedCW = Some(p)
//      case \/-(r) => println("Succeed!")
//    }
//
//    while (true) {
//      //Swing onEDTWait {tick.fire()}
//      Thread sleep 200
//      if(restartflag){
//        restartflag = false
//        suspendedCW match{
//          case Some(p) => ctx = Var(Continue);
//            p.exec(RC(ctx)) match {
//              case -\/(None) => suspendedCW = Some(visit(start0,maze))
//              case -\/(Some(p)) => suspendedCW = Some(p)
//              case \/-(r) => println("Succeed!")
//            }
//          case None => ()
//        }
//      }
//    }
  }
}
