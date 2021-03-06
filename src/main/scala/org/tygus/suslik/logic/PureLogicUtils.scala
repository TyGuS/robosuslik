package org.tygus.suslik.logic

import org.tygus.suslik.SSLException
import org.tygus.suslik.language.Expressions._
import org.tygus.suslik.language.Substitution

/**
  * Utilities for pure formulae
  *
  * @author Nadia Polikarpova, Ilya Sergey
  */
trait PureLogicUtils {
  // TODO Substitution has a mut part which doesn't make sense in this context.

  /*
  Substitutionitutions
   */
  //type Substitution = Map[Var, Expr]
//  type Subst[A] = Map[Var, Substitutable[A]]
  type Subst = Map[Var, Expr]
  type SubstVar = Map[Var, Var]

  def emptySubst[A]: Subst = Map.empty

  protected def assertNoConflict[A](sbst1: Substitution, sbst2: Substitution) {
    assert(sbst1.noConflict(sbst2), s"Two substitutions overlap:\n:$sbst1\n$sbst2")
  }

  def compose[A](subst1: SubstVar, subst2: Substitution): Substitution = {
    Substitution(subst1.map { case (k, v) => k -> subst2.getOrElse(v, v) },
      subst2.mutMapping)
    // TODO [Tag Substitution] potential bug when name clashes for mtags substitutions
  }

  def compose1[A](subst1: Substitution, subst2: Substitution): Substitution =
    Substitution(
      subst1.exprMapping.map {
      case (k, v) => k -> (v match {
        case w@Var(_) => subst2.exprMapping.getOrElse(w, v)
        case _ => v
      })
    }, subst1.mutMapping) // TODO [Tag Substitution] how to merge mutmappings


  def ppSubst(m: Substitution): String = {
    s"{${m.exprMapping.map { case (k, v) => s"${k.pp} -> ${v.pp}" }.mkString("; ")}" +
      s"${m.mutMapping.map { case (k, v) => s"${k.pp} -> ${v.pp}" }.mkString("; ")}}"
  }

  def agreeOnSameKeys[A](m1: Substitution, m2: Substitution): Boolean = {
    val common = m1.keyset.intersect(m2.keyset)
    common.forall(k => m1.sameValueAt(k, m2))
  }

  def desugar(e: Expr): Expr = e match {
      // Bi-implication usually ends up not being in CNF, so it is not very useful
//    case BinaryExpr(OpBoolEq, e1, e2) => desugar(e1) <==> desugar(e2)
//
//    case BinaryExpr(op, e1, e2) => BinaryExpr(op, desugar(e1), desugar(e2))
//    case UnaryExpr(op, e1) => UnaryExpr(op, desugar(e1))
    case _ => e
  }

  /**
    * Expression simplifier
    */
  def simplify(e: Expr): Expr = e match {
    //  Truth table for and
    case BinaryExpr(OpAnd, e1, e2) => simplify(e1) match {
      case BoolConst(false) => pFalse
      case BoolConst(true) => simplify(e2)
      case s1 => simplify(e2) match {
        case BoolConst(false) => pFalse
        case BoolConst(true) => s1
        case s2 => s1 && s2
      }
    }

    //  Truth table for or
    case BinaryExpr(OpOr, e1, e2) => simplify(e1) match {
      case BoolConst(true) => pTrue
      case BoolConst(false) => simplify(e2)
      case s1 => simplify(e2) match {
        case BoolConst(true) => pTrue
        case BoolConst(false) => s1
        case s2 => s1 || s2
      }
    }

    //  Classical logic stuff and de Morgan
    case UnaryExpr(OpNot, UnaryExpr(OpNot, arg)) => simplify(arg)
    case UnaryExpr(OpNot, BinaryExpr(OpAnd, left, right)) => simplify(left.not) || simplify(right.not)
    case UnaryExpr(OpNot, BinaryExpr(OpOr, left, right)) => simplify(left.not) && simplify(right.not)
    case UnaryExpr(OpNot, BoolConst(true)) => pFalse
    case UnaryExpr(OpNot, BoolConst(false)) => pTrue

    case BinaryExpr(OpEq, v1@Var(n1), v2@Var(n2)) if n1 == n2 => // remove trivial equality
      BoolConst(true)
    case BinaryExpr(OpEq, v1@Var(n1), v2@Var(n2)) => // sort arguments lexicographically
      if (n1 <= n2) BinaryExpr(OpEq, v1, v2) else BinaryExpr(OpEq, v2, v1)
    case BinaryExpr(OpEq, e, v@Var(_)) if !e.isInstanceOf[Var] => BinaryExpr(OpEq, v, simplify(e))
    case BinaryExpr(OpSetEq, v1@Var(n1), v2@Var(n2)) if n1 == n2 => // remove trivial equality
      BoolConst(true)
    case BinaryExpr(OpSetEq, v1@Var(n1), v2@Var(n2)) => // sort arguments lexicographically
      if (n1 <= n2) BinaryExpr(OpSetEq, v1, v2) else BinaryExpr(OpSetEq, v2, v1)
    case BinaryExpr(OpSetEq, e, v@Var(_)) if !e.isInstanceOf[Var] => BinaryExpr(OpSetEq, v, simplify(e))

      // TODO: maybe enable
//    case BinaryExpr(OpBoolEq, v1@Var(n1), v2@Var(n2)) if n1 == n2 => // remove trivial equality
//      BoolConst(true)
//    case BinaryExpr(OpBoolEq, v1@Var(n1), v2@Var(n2)) => // sort arguments lexicographically
//      if (n1 <= n2) BinaryExpr(OpBoolEq, v1, v2) else BinaryExpr(OpBoolEq, v2, v1)
//    case BinaryExpr(OpBoolEq, e, v@Var(_)) if !e.isInstanceOf[Var] => BinaryExpr(OpBoolEq, v, simplify(e))

    case BinaryExpr(OpPlus, left, IntConst(i)) if i.toInt == 0 => simplify(left)
    case BinaryExpr(OpPlus, IntConst(i), right) if i.toInt == 0 => simplify(right)
    case BinaryExpr(OpMinus, left, IntConst(i)) if i.toInt == 0 => simplify(left)

    case BinaryExpr(OpUnion, left, SetLiteral(s)) if s.isEmpty => simplify(left)
    case BinaryExpr(OpUnion, SetLiteral(s), right) if s.isEmpty => simplify(right)

    case UnaryExpr(op, e1) => UnaryExpr(op, simplify(e1))
    case BinaryExpr(op, e1, e2) => BinaryExpr(op, simplify(e1), simplify(e2))

    case _ => e
  }

  def pTrue: PFormula = BoolConst(true)

  def pFalse: PFormula = BoolConst(false)

  private def isAtomicExpr(e: Expr): Boolean = e match {
    case BinaryExpr(op, _, _) => !op.isInstanceOf[RelOp] && !op.isInstanceOf[LogicOp]
    case _ => true
  }

  val isRelationPFormula: (PFormula) => Boolean = {
    case BinaryExpr(op, e1, e2) => op.isInstanceOf[RelOp] && isAtomicExpr(e1) && isAtomicExpr(e2)
    case _ => false
  }

  val isAtomicPFormula: PFormula => Boolean = {
    case BoolConst(true) | BoolConst(false) => true
    case Var(_) => true // Not sure, because var might be non-bool, which is not very atomic (or is it atomic enough?)
    case UnaryExpr(OpNot, Var(_)) => true // here var must be bool
    case UnaryExpr(OpNot, p) => isRelationPFormula(p)
    case p => isRelationPFormula(p)
  }

  val isDisjunction: PFormula => Boolean = {
    case BinaryExpr(OpAnd, _, _) => false
    case BinaryExpr(OpOr, left, right) => isDisjunction(left) && isDisjunction(right)
    case p => isAtomicPFormula(p)
  }

  val isCNF: PFormula => Boolean = {
    case BinaryExpr(OpAnd, left, right) => isCNF(left) && isCNF(right)
    case p => isDisjunction(p)
  }

  def findCommon[T](cond: T => Boolean, ps1: List[T], ps2: List[T]): Option[(T, List[T], List[T])] = {
    for (p <- ps1 if cond(p)) {
      if (ps2.contains(p)) {
        return Some((p, ps1.filter(_ != p), ps2.filter(_ != p)))
      }
    }
    None
  }

  def findConjunctAndRest(p: PFormula => Boolean, phi: PFormula): Option[(PFormula, List[PFormula])] =
    Some(phi.conjuncts).flatMap(cs => cs.find(p) match {
      case Some(c) => Some((c, cs.filter(e => e != c)))
      case None => None
    })

  /**
    * Assemble a formula from a list of conjunctions
    */
  def mkConjunction(ps: List[PFormula]): PFormula = ps.distinct.foldLeft[PFormula](pTrue)((p1, p2) => p1 && p2)

  /**
    * @param vs    a list of variables to refresh
    * @param bound bound identifiers
    * @return A substitution from old vars in assn to new ones, fresh wrt. `rotten`
    */
  // TODO [Immutability] wrap in Substituiton here -- but it loses the Var, Var specificity
  def refreshVars(vs: List[Var], bound: Set[Var]): Map[Var, Var] = {

    def go(vsToRefresh: List[Var], taken: Set[Var], acc: Map[Var, Var]): Map[Var, Var] =
      vsToRefresh match {
        case Nil => acc
        case x :: xs =>
          val y = x.refresh(taken)
          val newAcc = acc + (x -> y)
          val newTaken = taken + x + y
          go(xs, newTaken, newAcc)
      }

    go(vs, bound, Map.empty)
  }

}

case class PureLogicException(msg: String) extends SSLException("pure", msg)