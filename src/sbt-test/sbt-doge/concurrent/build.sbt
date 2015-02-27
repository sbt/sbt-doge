import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean

lazy val latch = new CountDownLatch(2)

lazy val running = new AtomicBoolean(false)

lazy val countDown = TaskKey[Unit]("countDownAndBlock") := {
  latch.countDown()
  if (!latch.await(2, TimeUnit.SECONDS)) {
    sys.error("Count down timed out, the tasks must not have executed in parallel!")
  }
}

// This tries to detect concurrent execution when there shouldn't be
lazy val runExclusive = InputKey[Unit]("runExclusive") := {
  val args = Def.spaceDelimited("<arg>").parsed
  if (args.isEmpty) sys.error("no arguments supplied")
  if (running.getAndSet(true)) sys.error("already running")
  Thread.sleep(1000)
  running.set(false)
}

lazy val rootProj = (project in file("."))
  .enablePlugins(CrossPerProjectPlugin)
  .aggregate(a, b)

lazy val a = (project in file("a")).settings(
  countDown,
  runExclusive
)

lazy val b = (project in file("b")).settings(
  countDown,
  runExclusive
)

