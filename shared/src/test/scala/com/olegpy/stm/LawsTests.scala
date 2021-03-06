package com.olegpy.stm

import cats.effect.{IO, SyncIO}
import org.typelevel.discipline.Laws
import cats.laws.discipline._
import cats.laws.discipline.arbitrary._
import cats.effect.laws.discipline.arbitrary._
import cats.effect.laws.util.TestContext
import cats.effect.laws.util.TestInstances._
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.util.{ConsoleReporter, Pretty}
import utest._
import cats.implicits._
import cats.kernel.Eq
import cats.kernel.laws.discipline.MonoidTests
import utest.framework.TestPath
import com.olegpy.stm.misc.{TDeferred, TMVar, TQueue}

object LawsTests extends NondetIOSuite {
  val tests = Tests {
    "Monad[STM]" -
      uCheckAll(MonadTests[STM].monad[Int, String, Long])

    "Defer[STM]" -
      uCheckAll(DeferTests[STM].defer[Long])

    "MonoidK[STM]" -
      uCheckAll(MonoidKTests[STM].monoidK[Int])

    "Monoid[STM[A]]" -
      uCheckAll(MonoidTests[STM[Int]].monoid)

    "FunctorFilter[STM]" -
      uCheckAll(FunctorFilterTests[STM].functorFilter[Int, String, Long])

    "InvariantMonoidal[TRef]" -
      uCheckAll(InvariantMonoidalTests[TRef].invariantMonoidal[Int, Int, Int])

    "Invariant[TDeferred]" -
      uCheckAll(InvariantTests[TDeferred].invariant[Int, String, Long])

    "Invariant[TMVar]" -
      uCheckAll(InvariantTests[TMVar].invariant[Int, String, Long])

    "Invariant[TQueue]" -
      uCheckAll(InvariantTests[TQueue].invariant[Int, String, Long])
  }

  implicit val tc: TestContext = TestContext()
  implicit def arb[A](implicit a: Arbitrary[SyncIO[A]]): Arbitrary[STM[A]] =
    Arbitrary(a.arbitrary.map(_.toIO.asInstanceOf[STM[A]]))

  implicit def arbRef[A](implicit a: Arbitrary[A]): Arbitrary[TRef[A]] =
    Arbitrary(a.arbitrary.map(TRef.in[IO](_).unsafeRunSync()))

  implicit def arbTMVar[A](implicit a: Arbitrary[Option[A]]): Arbitrary[TMVar[A]] =
    Arbitrary(arbRef[Option[A]].arbitrary.map(new TMVar(_)))

  implicit def arbDef[A](implicit a: Arbitrary[Option[A]]): Arbitrary[TDeferred[A]] =
    Arbitrary(arbRef[Option[A]].arbitrary.map(new TDeferred(_)))

  implicit def arbQueue[A](implicit a: Arbitrary[List[A]]): Arbitrary[TQueue[A]] =
    Arbitrary {
      a.arbitrary.flatMap { initial =>
        val len = initial.length
        val allowSync = len <= 1
        val options = List(
          TQueue.bounded[A](len),
          TQueue.unbounded[A],
          TQueue.circularBuffer[A](len)
        ) ::: (if (allowSync) List(TQueue.synchronous[A]) else Nil)

        Gen.oneOf(options).map(q =>
          STM.tryCommitSync[IO, TQueue[A]](q.flatTap(_.enqueueAll(initial))).unsafeRunSync()
        )
      }
    }

  implicit def eq[A: Eq]: Eq[STM[A]] = Eq[IO[A]].contramap(STM.atomically[IO](_))

  implicit def eqRef[A: Eq: Arbitrary]: Eq[TRef[A]] = Eq.instance[TRef[A]] { (l, r) =>
    val next = implicitly[Arbitrary[A]].arbitrary.sample.get
    val check = (l.get, r.get).mapN(_ == _)
    STM.tryCommitSync[IO, Boolean] {
      (check, l.set(next), check).mapN((a, _, b) => a && b)
    }.unsafeRunSync()
  }

  implicit def eqTDeferred[A: Eq: Arbitrary]: Eq[TDeferred[A]] = Eq.by(_.state)
  implicit def eqTMVar[A: Eq: Arbitrary]: Eq[TMVar[A]] = Eq.by(_.state)
  implicit def eqTQueue[A: Eq: Arbitrary]: Eq[TQueue[A]] = Eq.instance { (q1, q2) =>
    STM.tryCommitSync[IO, Boolean]{
      for {
        _ <- q1.dequeueUpTo(Int.MaxValue)
        emptied  <- q2.isEmpty
      } yield emptied
    }.unsafeRunSync()
  }

  class UTestReporter(prop: String) extends ConsoleReporter(0) {
    override def onTestResult(name: String, res: org.scalacheck.Test.Result): Unit = {
      val scalaCheckResult = if (res.passed) "" else prop + " " + Pretty.pretty(res)
      assert(scalaCheckResult == "")
    }
  }

  private def uCheckAll(set: Laws#RuleSet)(implicit tp: TestPath): Unit =
    for ((name, prop) <- set.all.properties) {
      prop.check(_.withTestCallback(new UTestReporter(tp.value.last + name)))
    }
}
