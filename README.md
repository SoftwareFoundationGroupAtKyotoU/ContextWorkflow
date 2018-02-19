# ContextWorkflow

This is an embedded domain specific language for program interruption and compensation.

A simple example is:

```scala
import contextworkflow._
import cwutil._

var sum = 0
def add(i:Int):CW[Unit] = {sum += i} /+ {_ => sum -= i}
val add10:CW[Unit] = foreachCW(1 to 10)(add(_))
val ctx:Stream[Context] = Stream(Continue,Continue,Continue,Continue,Abort)
add10.exec(ctx) // sum = 0. Abort interruption occurs.
add10.exec() // sum = 55
```

`CW[A]` is an interruptible program which returns a value of the type `A` if succeeded.

## Constructs
### Primitive Workflow
A fundamental construct is a pair of normal and compensation action.

`normal /+ ((v:A) => compensation) : CW[A]`
where v is the result of normal action.

### Sequencing

Since CW is a monad of scalaz, we can use for-comprehension and many constructs provided by scalaz.

We also can use monadless style: `unlift` inside `lift`.

```scala
lift{
  val a = unlift{{1 + 2} /+ (println(_))}
  val b = unlift{{3 + 4} /+ (println(_))}
  a + b
} : CW[Int]
```

### Context
- `Continue` : continuing the execution 
- `Abort` : interrupting the program and do compensations
- `PAbort` : roll-backing to the nearest checkpoint
- `Suspend` : suspending the execution and returning the rest CW 

### Sub-workflow
An inner workflow that skips its compensations if completed. 

`def sub[A](cw: CW[A]): CW[A]`

### Checkpoint

`val checkpoint: CW[Unit]`

## Requirements
- sbt 0.13

## Demos
`sbt examples/run`

and choose one.

Online REPLs are available

Basic: https://scastie.scala-lang.org/h-inoue/NF9nSWfLR9S9BwAH0bEI1Q/1

Package Manager: https://scastie.scala-lang.org/h-inoue/73tBbDTwSR2z8F5zYmfl5w/1 

## Using ContextWorkflow

`CW` becomes a monad of scalaz so we can use many constructs introduced in scalaz.
