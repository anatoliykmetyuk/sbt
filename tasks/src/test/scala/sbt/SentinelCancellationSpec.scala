/*
 * sbt
 * Copyright 2026, Scala Center, and the sbt contributors.
 * Licensed under Apache License 2.0 (see LICENSE).
 */

package sbt

import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import verify.BasicTestSuite

object SentinelCancellationSpec extends BasicTestSuite {
  for (sentinelWorker <- List(false, true)) {
    test(
      s"a failing worker cancels only sentinels in its own evaluation (sentinel=$sentinelWorker)"
    ) {
      val pool = Executors.newFixedThreadPool(3)
      val oldStarted = new CountDownLatch(1)
      val newStarted = new CountDownLatch(1)
      val trigger = new CountDownLatch(1)
      val cancelled = new CountDownLatch(1)
      val oldInterrupted = new CountDownLatch(1)
      val releaseOld = new CountDownLatch(1)
      val releaseNew = new CountDownLatch(1)
      val newFinished = new CountDownLatch(1)
      final class Id(val sentinel: Boolean) extends TaskId[Unit] {
        def tags: ConcurrentRestrictions.TagMap = Map.empty
      }
      def service() = ConcurrentRestrictions.completionService(
        pool,
        ConcurrentRestrictions.unrestricted,
        _ => (),
        _.asInstanceOf[Id].sentinel
      )
      val oldService = service()
      val newService = service()
      def await(latch: CountDownLatch): Unit = assert(latch.await(10, TimeUnit.SECONDS))
      try {
        oldService.submit(
          new Id(true),
          () => {
            oldStarted.countDown()
            try await(releaseOld)
            catch { case _: InterruptedException => oldInterrupted.countDown() }
            Execute.completed(())
          }
        )
        await(oldStarted)
        oldService.submit(
          new Id(sentinelWorker),
          () => {
            await(trigger)
            ConcurrentRestrictions.cancelCurrentSentinels()
            cancelled.countDown()
            Execute.completed(())
          }
        )
        newService.submit(
          new Id(true),
          () => {
            newStarted.countDown()
            try await(releaseNew)
            finally newFinished.countDown()
            Execute.completed(())
          }
        )
        await(newStarted)
        ConcurrentRestrictions.cancelCurrentSentinels()
        trigger.countDown()
        await(cancelled)
        await(oldInterrupted)
        releaseNew.countDown()
        await(newFinished)
        newService.take().process()
      } finally {
        trigger.countDown()
        releaseOld.countDown()
        releaseNew.countDown()
        oldService.close()
        newService.close()
        pool.shutdownNow()
        ()
      }
    }
  }

  test("completion service ownership is cleared before a pooled worker is reused") {
    val pool = Executors.newFixedThreadPool(2)
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    final class Id(val sentinel: Boolean) extends TaskId[Unit] {
      def tags: ConcurrentRestrictions.TagMap = Map.empty
    }
    val service = ConcurrentRestrictions.completionService(
      pool,
      ConcurrentRestrictions.unrestricted,
      _ => (),
      _.asInstanceOf[Id].sentinel
    )
    try {
      service.submit(
        new Id(true),
        () => {
          started.countDown()
          assert(release.await(10, TimeUnit.SECONDS))
          Execute.completed(())
        }
      )
      assert(started.await(10, TimeUnit.SECONDS))
      service.submit(new Id(false), () => Execute.completed(()))
      service.take().process()
      pool
        .submit(new Runnable {
          def run(): Unit = ConcurrentRestrictions.cancelCurrentSentinels()
        })
        .get(10, TimeUnit.SECONDS)
      release.countDown()
      service.take().process()
    } finally {
      release.countDown()
      service.close()
      pool.shutdownNow()
      ()
    }
  }

}
