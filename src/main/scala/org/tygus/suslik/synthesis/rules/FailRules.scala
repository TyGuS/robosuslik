package org.tygus.suslik.synthesis.rules

import org.tygus.suslik.language.Expressions.{Expr, Var}
import org.tygus.suslik.language.{Ident, IntType}
import org.tygus.suslik.language.Statements.{Guarded, If}
import org.tygus.suslik.logic.Specifications.Goal
import org.tygus.suslik.logic.smt.SMTSolving
import org.tygus.suslik.logic.{PureLogicUtils, SepLogicUtils}
import org.tygus.suslik.synthesis.rules.Rules._
import org.tygus.suslik.synthesis.rules.OperationalRules.{AllocRule, FreeRule}

/**
  * Rules for short-circuiting failure;
  * do not affect completeness, they are simply an optimization.
  * @author Nadia Polikarpova, Ilya Sergey
  */

object FailRules extends PureLogicUtils with SepLogicUtils with RuleUtils {

  val exceptionQualifier: String = "rule-fail"

  // Noop rule: never applies (used to replace disabled rules)
  object Noop extends SynthesisRule with AnyPhase {
    override def toString: String = "Noop"

    def apply(goal: Goal): Seq[RuleResult] = Nil
  }

  // Short-circuits failure if pure post is inconsistent with the pre
  object PostInconsistent extends SynthesisRule with AnyPhase with InvertibleRule {
    override def toString: String = "PostInconsistent"

    def apply(goal: Goal): Seq[RuleResult] = {
      val pre = goal.pre.phi
      val post = goal.post.phi

      if (!SMTSolving.sat(pre && post))
        // post inconsistent with pre
        List(RuleResult(List(goal.unsolvableChild), idProducer, toString))
      else
        Nil
    }
  }

  // Short-circuits failure if universal part of post is too strong
  object PostInvalid extends SynthesisRule with FlatPhase with InvertibleRule {
    override def toString: String = "PostInvalid"

    def apply(goal: Goal): Seq[RuleResult] = {
      // If precondition does not contain predicates, we can't get get new facts from anywhere
      if (!SMTSolving.valid(goal.pre.phi ==> goal.universalPost))
        // universal post not implies by pre
        List(RuleResult(List(goal.unsolvableChild), idProducer, toString))
      else
        Nil
    }
  }

  object AbduceBranch extends SynthesisRule with FlatPhase with InvertibleRule {
    override def toString: String = "AbduceBranch"

    def atomCandidates(goal: Goal): Seq[Expr] =
      for {
        lhs <- goal.programVars
        rhs <- goal.programVars
        if lhs != rhs
        if goal.getType(lhs) == IntType && goal.getType(rhs) == IntType
      } yield lhs |<=| rhs

    def condCandidates(goal: Goal): Seq[Expr] = {
      val atoms = atomCandidates(goal)
      // Toggle this to enable abduction of conjunctions
      // (without branch pruning, produces too many branches)
//      atoms
      for {
        subset <- atoms.toSet.subsets.toSeq
        if subset.nonEmpty
      } yield simplify(mkConjunction(subset.toList))
    }

    /**
      * Find the earliest ancestor of goal
      * that is still valid and has all variables from vars
      */
    def findBranchPoint(vars: Set[Var], goal: Goal, valid: Boolean): Option[Goal] = goal.parent match {
      case None => if (valid) Some(goal) else None // goal is root: return itself if valid
      case Some(pGoal) =>
        if (vars.subsetOf(pGoal.programVars.toSet)) {
          // Parent goal has all variables from vars: recurse
          val pCons = SMTSolving.valid(pGoal.pre.phi ==> pGoal.universalPost)
          findBranchPoint(vars, pGoal, pCons)
        } else if (valid) Some(goal) else None // one of vars undefined in the goal: return itself if valid
    }

    def guardedCandidates(goal: Goal): Seq[RuleResult] =
      for {
        cond <- condCandidates(goal)
        pre = goal.pre.phi
        if SMTSolving.valid((pre && cond) ==> goal.universalPost)
        if SMTSolving.sat(pre && cond)
        bGoal <- findBranchPoint(cond.vars, goal, false)
        thenGoal = goal.spawnChild(goal.pre.copy(phi = goal.pre.phi && cond), childId = Some(0))
        elseGoal = goal.spawnChild(
          pre = bGoal.pre.copy(phi = bGoal.pre.phi && cond.not),
          post = bGoal.post,
          gamma = bGoal.gamma,
          programVars = bGoal.programVars,
          childId = Some(1))
      } yield RuleResult(List(thenGoal, elseGoal),
        StmtProducer(2, liftToSolutions(stmts => Guarded(cond, stmts.head, stmts.last, bGoal.label))),
        toString)

    def apply(goal: Goal): Seq[RuleResult] = {
      if (SMTSolving.valid(goal.pre.phi ==> goal.universalPost))
        Nil // valid so far, nothing to say
      else {
        val guarded = guardedCandidates(goal)
        if (guarded.isEmpty)
          // Abduction failed
          if (goal.env.config.fail) List(RuleResult(List(goal.unsolvableChild), idProducer, toString)) // pre doesn't imply post: goal is unsolvable
          else Nil // fail optimization is disabled, so pretend this rule doesn't apply
        else guarded
      }
    }
  }


  // Short-circuits failure if spatial post doesn't match pre
  // This rule is only applicable if alloc and free aren't
  object HeapUnreachable extends SynthesisRule with FlatPhase with InvertibleRule {
    override def toString: String = "HeapUnreachable"

    def apply(goal: Goal): Seq[RuleResult] = {
      (AllocRule.findTargetHeaplets(goal), FreeRule.findTargetHeaplets(goal)) match {
        case (None, None) =>
          if (goal.pre.sigma.chunks.length == goal.post.sigma.chunks.length)
          // TODO will we always reach ReadOnlyEmpty before this rule?
            Nil
          else if (goal.pre.sigma.chunks.foldLeft[Boolean](true)((acc, h) =>
            if (h.isMutable) false
            else acc)) Nil
          else
            List(RuleResult(List(goal.unsolvableChild), idProducer, toString)) // spatial parts do not match: only magic can save us
        case _ => Nil // does not apply if we could still alloc or free
      }

    }
  }
}
