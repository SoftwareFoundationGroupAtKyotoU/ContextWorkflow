# ContextWorkflow
under construction

A simple example is:

```scala
import contextworkflow._
import cwutil._

var sum = 0
def add(i:Int):CW[Unit] = {sum += i} /+ {_ => sum -= i}
val add10:CW[Unit] = foreachCW(1 to 10)(add(_))
val ctx:Stream[Context] = Stream(Continue,Continue,Continue,Continue,Abort)
add10.exec(ctx) // sum = 0
add10.exec() // sum = 55
```

## Requirements
- sbt 0.13

## Demos
`sbt examples/run`

and choose one.

## Using ContextWorkflow

`CW` becomes a monad of scalaz so we can use many constructs introduced in scalaz.