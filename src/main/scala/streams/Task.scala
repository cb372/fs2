package streams

import java.util.concurrent.{ScheduledExecutorService, ConcurrentLinkedQueue, ExecutorService, Executors}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import collection.JavaConversions._
import scala.concurrent.duration._
import streams.util.{Actor,Strategy,UF1}

/*
`Task` is a trampolined computation producing an `A` that may
include asynchronous steps. Arbitrary monadic expressions involving
`map` and `flatMap` are guaranteed to use constant stack space.
In addition, using `Task.async`, one may construct a `Task` from
callback-based APIs. This makes `Task` useful as a concurrency primitive
and as a control structure for wrapping callback-based APIs with a more
straightforward, monadic API.

Task is also exception-safe. Any exceptions raised during processing may
be accessed via the `attempt` method, which converts a `Task[A]` to a
`Task[Either[Throwable,A]]`.

Unlike the `scala.concurrent.Future` type introduced in scala 2.10,
`map` and `flatMap` do NOT spawn new tasks and do not require an implicit
`ExecutionContext`. Instead, `map` and `flatMap` merely add to
the current (trampolined) continuation that will be run by the
'current' thread, unless explicitly forked via `Task.start` or
`Future.apply`. This means that `Future` achieves much better thread
reuse than the 2.10 implementation and avoids needless thread
pool submit cycles.

`Task` also differs from the `scala.concurrent.Future` type in that it
does not represent a _running_ computation. Instead, we
reintroduce concurrency _explicitly_ using the `Task.start` function.
This simplifies our implementation and makes code easier to reason about,
since the order of effects and the points of allowed concurrency are made
fully explicit and do not depend on Scala's evaluation order.
*/
class Task[+A](val get: Future[Either[Throwable,A]]) {

  def flatMap[B](f: A => Task[B]): Task[B] =
    new Task(get flatMap {
      case Left(e) => Future.now(Left(e))
      case Right(a) => Task.Try(f(a)) match {
        case Left(e) => Future.now(Left(e))
        case Right(task) => task.get
      }
    })

  def map[B](f: A => B): Task[B] =
    new Task(get map { _.right.flatMap { a => Task.Try(f(a)) } })

  /** 'Catches' exceptions in the given task and returns them as values. */
  def attempt: Task[Either[Throwable,A]] =
    new Task(get map {
      case Left(e) => Right(Left(e))
      case Right(a) => Right(Right(a))
    })

  /**
   * Returns a new `Task` in which `f` is scheduled to be run on completion.
   * This would typically be used to release any resources acquired by this
   * `Task`.
   */
  def onFinish(f: Option[Throwable] => Task[Unit]): Task[A] =
    new Task(get flatMap {
      case Left(e) => f(Some(e)).get flatMap { _ => Future.now(Left(e)) }
      case r => f(None).get flatMap { _ => Future.now(r) }
    })

  /**
   * Calls `attempt` and handles some exceptions using the given partial
   * function, calling Task.now on the result. Any nonmatching exceptions
   * are reraised.
   */
  def handle[B>:A](f: PartialFunction[Throwable,B]): Task[B] =
    handleWith(f andThen Task.now)

  /**
   * Calls `attempt` and handles some exceptions using the given partial
   * function. Any nonmatching exceptions are reraised.
   */
  def handleWith[B>:A](f: PartialFunction[Throwable,Task[B]]): Task[B] =
    attempt flatMap {
      case Left(e) => f.lift(e) getOrElse Task.fail(e)
      case Right(a) => Task.now(a)
    }

  /**
   * Runs this `Task`, and if it fails with an exception, runs `t2`.
   * This is rather coarse-grained. Use `attempt`, `handle`, and
   * `flatMap` for more fine grained control of exception handling.
   */
  def or[B>:A](t2: Task[B]): Task[B] =
    new Task(this.get flatMap {
      case Left(e) => t2.get
      case a => Future.now(a)
    })

  /**
   * Run this `Task` and block until its result is available. This will
   * throw any exceptions generated by the `Task`. To return exceptions
   * in an `\/`, use `attemptRun`.
   */
  def run: A = get.run match {
    case Left(e) => throw e
    case Right(a) => a
  }

  /** Like `run`, but returns exceptions as values. */
  def attemptRun: Either[Throwable,A] =
    try get.run catch { case t: Throwable => Left(t) }

  /**
   * Run this computation to obtain an `A`, so long as `cancel` remains false.
   * Because of trampolining, we get frequent opportunities to cancel
   * while stepping through the trampoline, this should provide a fairly
   * robust means of cancellation.
   */
  def runAsyncInterruptibly(f: Either[Throwable,A] => Unit, cancel: AtomicBoolean): Unit =
    get.runAsyncInterruptibly(f, cancel)

  /**
   * Similar to `runAsyncInterruptibly(f,cancel)` except instead of interrupting
   * by setting cancel to true, it returns the function, that, when applied will
   * interrupt the task.
   *
   * This allows "deterministic" completion of task computation even if it was
   * interrupted. That means task will complete even when interrupted,
   * but with `TaskInterrupted` exception.
   *
   * Note 1: When Interrupted, the `f` callback will run in thread that
   *         called the `Interrupting` function `() => Unit`
   * Note 2: If task has handler like attempt, it won't get consulted
   *         for handling TaskInterrupted excpetion
   */
  def runAsyncInterruptibly(f: Either[Throwable,A] => Unit): () => Unit = {
    val completed : AtomicBoolean = new AtomicBoolean(false)
    val a = Actor[Option[Either[Throwable,A]]] ({
      case Some(r) if ! completed.get =>
        completed.set(true)
        f(r)
      case None if ! completed.get  =>
        completed.set(true)
        f(Left(Task.TaskInterrupted))
      case _ => () //already completed
    })(Strategy.sequential)

    get.runAsyncInterruptibly(r => a ! Some(r), completed)
    () => { a ! None }
  }

  /**
   * Run this computation to obtain either a result or an exception, then
   * invoke the given callback. Any pure, non-asynchronous computation at the
   * head of this `Future` will be forced in the calling thread. At the first
   * `Async` encountered, control to whatever thread backs the `Async` and
   * this function returns immediately.
   */
  def runAsync(f: Either[Throwable,A] => Unit): Unit =
    get.runAsync(f)

  /**
   * Run this `Task` and block until its result is available, or until
   * `timeoutInMillis` milliseconds have elapsed, at which point a `TimeoutException`
   * will be thrown and the `Future` will attempt to be canceled.
   */
  def runFor(timeoutInMillis: Long): A = get.runFor(timeoutInMillis) match {
    case Left(e) => throw e
    case Right(a) => a
  }

  def runFor(timeout: Duration): A = runFor(timeout.toMillis)

  /**
   * Like `runFor`, but returns exceptions as values. Both `TimeoutException`
   * and other exceptions will be folded into the same `Throwable`.
   */
  def attemptRunFor(timeoutInMillis: Long): Either[Throwable,A] =
    get.attemptRunFor(timeoutInMillis).right flatMap { a => a }

  def attemptRunFor(timeout: Duration): Either[Throwable,A] =
    attemptRunFor(timeout.toMillis)

  /**
   * A `Task` which returns a `TimeoutException` after `timeoutInMillis`,
   * and attempts to cancel the running computation.
   */
  def timed(timeoutInMillis: Long)(implicit S: ScheduledExecutorService): Task[A] =
    new Task(get.timed(timeoutInMillis).map(_.right.flatMap(x => x)))

  def timed(timeout: Duration)(implicit S: ScheduledExecutorService): Task[A] =
    timed(timeout.toMillis)

  /**
   * Retries this task if it fails, once for each element in `delays`,
   * each retry delayed by the corresponding duration, accumulating
   * errors into a list.
   * A retriable failure is one for which the predicate `p` returns `true`.
   */
  def retryAccumulating(delays: Seq[Duration],
                        p: (Throwable => Boolean) = _.isInstanceOf[Exception])(
                        implicit S: ScheduledExecutorService): Task[(A, List[Throwable])] =
    retryInternal(delays, p, true)

  /**
   * Retries this task if it fails, once for each element in `delays`,
   * each retry delayed by the corresponding duration.
   * A retryable failure is one for which the predicate `p` returns `true`.
   */
  def retry(delays: Seq[Duration], p: (Throwable => Boolean) = _.isInstanceOf[Exception])(
       implicit S: ScheduledExecutorService): Task[A] =
    retryInternal(delays, p, false).map(_._1)

  private def retryInternal(delays: Seq[Duration],
                            p: Throwable => Boolean,
                            accumulateErrors: Boolean)
                            (implicit S: ScheduledExecutorService):
                            Task[(A, List[Throwable])] = {
      def help(ds: Seq[Duration], es: => collection.immutable.Stream[Throwable]):
      Future[Either[Throwable, (A, List[Throwable])]] = {
        def acc = if (accumulateErrors) es.toList else Nil
          ds match {
            case Seq() => get map (_.right.map(_ -> acc))
            case Seq(t, ts @_*) => get flatMap {
              case Left(e) if p(e) =>
                help(ts, e #:: es) after t
              case x => Future.now(x.right.map(_ -> acc))
            }
        }
      }
      Task.async { help(delays, Stream()).runAsync }
    }

  /**
   Ensures the result of this Task satisfies the given predicate,
   or fails with the given value.
   */
  def ensure(failure: => Throwable)(f: A => Boolean): Task[A] =
    flatMap(a => if(f(a)) Task.now(a) else Task.fail(failure))

  /**
   Returns a `Task` that, when run, races evaluation of `this` and `t`,
   and returns the result of whichever completes first. The losing task
   continues to execute in the background though its result will be sent
   nowhere.
   */
  def race[B](t: Task[B])(implicit S: Strategy): Task[Either[A,B]] = {
    Task.ref[Either[A,B]].flatMap { pool =>
      pool.setRace(this map (Left(_)), t map (Right(_)))
          .flatMap { _ => pool.get }
    }
  }
}

object Task extends Instances {

  /** Special exception signalling that the task was interrupted. **/
  case object TaskInterrupted extends InterruptedException {
    override def fillInStackTrace = this
  }

  /** A `Task` which fails with the given `Throwable`. */
  def fail(e: Throwable): Task[Nothing] = new Task(Future.now(Left(e)))

  /** Convert a strict value to a `Task`. Also see `delay`. */
  def now[A](a: A): Task[A] = new Task(Future.now(Right(a)))

  /**
   * Promote a non-strict value to a `Task`, catching exceptions in
   * the process. Note that since `Task` is unmemoized, this will
   * recompute `a` each time it is sequenced into a larger computation.
   * Memoize `a` with a lazy value before calling this function if
   * memoization is desired.
   */
  def delay[A](a: => A): Task[A] = suspend(now(a))

  /**
   * Produce `f` in the main trampolining loop, `Future.step`, using a fresh
   * call stack. The standard trampolining primitive, useful for avoiding
   * stack overflows.
   */
  def suspend[A](a: => Task[A]): Task[A] = new Task(Future.suspend(
    Try(a.get) match {
      case Left(e) => Future.now(Left(e))
      case Right(f) => f
  }))

  /** Create a `Future` that will evaluate `a` using the given `ExecutorService`. */
  def apply[A](a: => A)(implicit S: Strategy): Task[A] =
    new Task(Future(Try(a)))

  /**
   * Don't use this. It doesn't do what you think. If you have a `t: Task[A]` you'd
   * like to evaluate concurrently, use `Task.start(t) flatMap { ft => ..}`.
   */
  @deprecated(message = "use `Task.start`", since = "0.8")
  def fork[A](a: => Task[A])(implicit S: Strategy): Task[A] =
    apply(a) flatMap { a => a }

  /**
   Given `t: Task[A]`, `start(t)` returns a `Task[Task[A]]`. After `flatMap`-ing
   into the outer task, `t` will be running in the background, and the inner task
   is conceptually a future which can be forced at any point via `flatMap`.

   For example:

   {{{
     for {
       f <- Task.start { expensiveTask1 }
       // at this point, `expensive1` is evaluating in background
       g <- Task.start { expensiveTask2 }
       // now both `expensive2` and `expensive1` are running
       result1 <- f
       // we have forced `f`, so now only `expensive2` may be running
       result2 <- g
       // we have forced `g`, so now nothing is running and we have both results
     } yield (result1 + result2)
   }}}
  */
  def start[A](t: Task[A])(implicit S: Strategy): Task[Task[A]] =
    ref[A].flatMap { ref => ref.set(t) map (_ => ref.get) }

  /**
   Create a `Future` from an asynchronous computation, which takes the form
   of a function with which we can register a callback. This can be used
   to translate from a callback-based API to a straightforward monadic
   version. See `Task.async` for a version that allows for asynchronous
   exceptions.
   */
  def async[A](register: (Either[Throwable,A] => Unit) => Unit): Task[A] =
    new Task(Future.async(register))

  /** Utility function - evaluate `a` and catch and return any exceptions. */
  def Try[A](a: => A): Either[Throwable,A] =
    try Right(a) catch { case e: Throwable => Left(e) }

  def TryTask[A](a: => Task[A]): Task[A] =
    try a catch { case e: Throwable => fail(e) }

  def ref[A](implicit S: Strategy): Task[Ref[A]] = Task.delay {
    type Get = Either[Throwable,A] => Unit
    var result: Either[Throwable,A] = null
    var waiting: List[Get] = List()
    val act = Actor.actor[Either[Get, Either[Throwable,A]]] {
      case Left(cb) =>
        if (!(result eq null)) S { cb(result) }
        else waiting = cb :: waiting
      case Right(r) =>
        result = r
        waiting.reverse.foreach(cb => S { cb(r) })
        waiting = List()
    } (S)
    new Ref(act)
  }

  class Ref[A](actor: Actor[Either[Either[Throwable,A] => Unit, Either[Throwable,A]]]) {
    /**
     * Return a `Task` that submits `t` to this pool for evaluation.
     * When it completes it overwrites any previously `put` value.
     */
    def set(t: Task[A]): Task[Unit] = Task.delay { t.runAsync { r => actor ! Right(r) } }
    def setFree(t: Free[Task,A]): Task[Unit] = set(t.run(UF1.id))

    /** Return the most recently completed `set`, or block until a `set` value is available. */
    def get: Task[A] = Task.async { cb => actor ! Left(cb) }

    /**
     * Runs `t1` and `t2` simultaneously, but only the winner gets to
     * `set` to this `Pool`. The loser continues running but its reference
     * to this pool is severed, allowing this pool to be garbage collected
     * if it is no longer referenced by anyone other than the loser.
     */
    def setRace(t1: Task[A], t2: Task[A]): Task[Unit] = Task.delay {
      val ref = new AtomicReference(actor)
      val won = new AtomicBoolean(false)
      val win = (res: Either[Throwable,A]) => {
        // important for GC: we don't reference this pool
        // or the actor directly, and the winner destroys any
        // references behind it!
        if (won.compareAndSet(false, true)) {
          val actor = ref.get
          ref.set(null)
          actor ! Right(res)
        }
      }
      t1.runAsync(win)
      t2.runAsync(win)
    }
  }
}

/* Prefer an `Async`, but will settle for implicit `Monad`. */
private[streams] trait Instances1 {
  implicit def monad: Monad[Task] = new Monad[Task] {
    def pure[A](a: A) = Task.now(a)
    def bind[A,B](a: Task[A])(f: A => Task[B]): Task[B] = a flatMap f
  }
}

private[streams] trait Instances extends Instances1 {
  implicit def AsyncInstance(implicit S: Strategy): Async[Task] = new Async[Task] {
    type Ref[A] = Task.Ref[A]
    def pure[A](a: A) = Task.now(a)
    def bind[A,B](a: Task[A])(f: A => Task[B]): Task[B] = a flatMap f
    def ref[A] = Task.ref[A](S)
    def set[A](p: Ref[A])(t: Task[A]) = p.set(t)
    def setFree[A](p: Ref[A])(t: Free[Task,A]) = p.setFree(t)
    def get[A](p: Ref[A]): Task[A] = p.get
    def race[A,B](t1: Task[A], t2: Task[B]): Task[Either[A,B]] = t1 race t2
  }
}
