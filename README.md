# ContextWorkflow

An embedded domain specific language (E-DSL) for program interruption and compensation.
This E-DSL mainly supports ...

- Interruption like asynchronous exceptions
- Roll-backing like transactions and Software Transactional Memory
- Suspension like delimited continuations

A simple example is:

```scala
import contextworkflow._
import cwutil._

var sum = 0
def add(i:Int):CW[Unit] = {sum += i} /+ {_ => sum -= i}
val add10:CW[Unit] = foreachCW(1 to 10)(add(_))
val ctx:Stream[Context] = Stream(Continue,Continue,Continue,Continue,Abort)
add10.exec(ctx) // sum = 0. An interruption occurs and the execution is rolled back.
add10.exec() // sum = 55
```

The execution of `add10.exec(ctx)` would actually be
```
sum += 1; sum += 2; sum += 3; sum += 4;
sum -= 4; sum -= 3; sum -= 2; sum -= 1;
```
That is, the program is interrupted before `sub += 5` and 
rolled back (doing subtractions).

The type `CW[A]` embodies an interruptible program which returns a value of the type `A` if succeeded.
The `exec` method can take an argument, which is basically a time-varying stream.

## Constructs
An interruptible program in this E-DSL is a workflow, which is a sequence of 
primitive workflows. Primitive workflows are monads and 
ContextWorkflow assemble them in monadic way.

### Primitive Workflow
A pair of normal and compensation action.
Basically, a compensation action becomes reversal of a normal action.
```scala
def /+[A](normal: A)(compensation: A => Unit): CW[A]

normal /+ compensation : CW[A] // primitive workflow
```
where the argument of `compensation` becomes the result of the `normal` action.
Normal and compensation actions can be any effectful code.

### Sequencing Workflows

Since CW is a monad of [scalaz](https://github.com/scalaz/scalaz),
we can use for-comprehension and many constructs provided by scalaz.

We also can use [monadless](https://github.com/monadless/monadless#how-does-it-work) style: `unlift` inside `lift`.

```scala
def unlift[A](cw: CW[A]): A
def lift[A](a: A): CW[A]

lift{
  val a = unlift{{1 + 2} /+ (println(_))}
  val b = unlift{{3 + 4} /+ (println(_))}
  a + b
} : CW[Int]
```

### Context
Context is what manages interruption. Context consists of four elements.

- `Continue` : continuing the execution 
- `Abort` : interrupting the program and do compensations
- `PAbort` : roll-backing to the nearest checkpoint
- `Suspend` : suspending the execution and returning the rest CW

`CW` class has an `exec` method.
```scala
def exec(ctx:Seq[Context]): \/[Option[CW[A]],A] 
```
The argument is of the type `Seq[Context]`.
In demos, basically we use `List[Context]` or `Stream[Context]`.
Here, `Seq[Context]` expresses an iterator.
Basically, one Context is consumed before an execution of a primitive workflow.

The `exec` method returns `\/[Option[CW[A]],A]`, where `\/[A,B]` is disjunction type in scalaz.
Actually, the value becomes one of following three cases.
- `-\/(Some(cw))` : suspended workflow, where `cw:CW[A]` 
- `-\/(None)` : aborted workflow 
- `\/-(a)` : succeeded workflow
 
The argument also can be `Signal[Context]`, where `Signal` is of [REScala](https://github.com/guidosalva/REScala) 
library, which realizes Functional Reactive Programming in Scala.
Using signals, intuitively ContextWorkflow generalizes time-out; 
while time-out execution usually takes time-limit, this takes signal of context.

### Sub-workflow
An inner workflow that skips its compensations if completed. 

```scala
def sub[A](cw: CW[A]): CW[A]
```

### Checkpoint

```scala
val checkpoint: CW[Unit]
```

### Atomicity

```scala
def atomic[A](cw: CW[A]): CW[A]
def nonatomic[A](cw: CW[A]): CW[A]
```

### Exception to Context

```scala
def /~[A](normal: A)(comp: A => Unit)
```

## Requirements
- sbt 0.13
- scalaz 7.2

## Demos
Do `sbt examples/run`
and choose one.

Additionally, online REPLs are available.

Basic: (https://scastie.scala-lang.org/h-inoue/FYckcE1LSyagFDklrc6oPw/1)

Package Manager: (https://scastie.scala-lang.org/h-inoue/73tBbDTwSR2z8F5zYmfl5w/1) 
