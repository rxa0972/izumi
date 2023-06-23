package izumi.distage.testkit.runner.impl

import distage.{DIKey, Planner, PlannerInput}
import izumi.distage.model.plan.Plan
import izumi.distage.testkit.model.{FailedTest, PreparedTest, TestGroup, TestTree}
import izumi.distage.testkit.runner.impl.TestPlanner.PackedEnv
import izumi.distage.testkit.runner.impl.services.TimedAction

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer

trait TestTreeBuilder[F[_]] {
  def build(planner: Planner, runtimePlan: Plan, iterator: Iterable[PackedEnv[F]]): TestTree[F]
}

object TestTreeBuilder {
  class TestTreeBuilderImpl[F[_]](
    timed: TimedAction
  ) extends TestTreeBuilder[F] {
    final class MemoizationTreeBuilder(planner: Planner, runtimePlan: Plan) {

      private[this] val children = TrieMap.empty[Plan, MemoizationTreeBuilder]
      private[this] val groups = ArrayBuffer.empty[PackedEnv[F]]

      def toImmutable(parentKeys: Set[DIKey]): TestTree[F] = {
        val levelGroups = groups.map {
          env =>
            val tests = env.preparedTests.map {
              t =>
                val allSharedKeys = parentKeys ++ runtimePlan.keys
                val newAppModule = t.appModule.drop(allSharedKeys)
                val newRoots = t.targetKeys -- allSharedKeys ++ env.strengthenedKeys.intersect(newAppModule.keys)

                (
                  t,
                  for {
                    maybeNewTestPlan <- timed {
                      if (newRoots.nonEmpty) {
                        /** (1) It's important to remember that .plan() would always return the same result regardless of the parent locator!
                          * (2) The planner here must preserve customizations (bootstrap modules) hence be the same as instantiated in TestPlanner
                          */
                        planner.plan(PlannerInput(newAppModule, t.activation, newRoots))
                      } else {
                        Right(Plan.empty)
                      }
                    }.invert
                  } yield {
                    PreparedTest(
                      t.test,
                      maybeNewTestPlan,
                      newRoots,
                    )
                  },
                )
            }

            val goodTests = tests.collect {
              case (_, Right(value)) => value
            }.toList

            val badTests = tests.collect {
              case (t, Left(value)) => FailedTest(t.test, value)
            }.toList

            TestGroup(goodTests, badTests, env.strengthenedKeys)
        }.toList

        val children1 = children.map(_._2.toImmutable(parentKeys ++ runtimePlan.keys)).toList
        TestTree(runtimePlan, levelGroups, children1, parentKeys)
      }

      @tailrec def addGroupByPath(path: List[Plan], env: PackedEnv[F]): Unit = {
        path match {
          case Nil =>
            groups.synchronized(groups.append(env))
            ()
          case node :: tail =>
            val childTree = children.synchronized(children.getOrElseUpdate(node, new MemoizationTreeBuilder(planner, node)))
            childTree.addGroupByPath(tail, env)
        }
      }
    }

    override def build(planner: Planner, runtimePlan: Plan, iterator: Iterable[PackedEnv[F]]): TestTree[F] = {
      val tree = new MemoizationTreeBuilder(planner, runtimePlan)
      // usually, we have a small amount of levels, so parallel executions make only worse here
      iterator.foreach {
        env =>
          val plans = env.memoizationPlanTree.filter(_.plan.meta.nodes.nonEmpty)
          tree.addGroupByPath(plans, env)
      }
      tree.toImmutable(runtimePlan.keys)
    }
  }
}
