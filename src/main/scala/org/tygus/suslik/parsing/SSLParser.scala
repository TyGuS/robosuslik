package org.tygus.suslik.parsing

import org.tygus.suslik.language.Expressions._
import org.tygus.suslik.language._
import org.tygus.suslik.logic._
import org.tygus.suslik.logic.unification.UnificationGoal
import org.tygus.suslik.logic.Specifications._
import org.tygus.suslik.synthesis.{SynthesisException,defaultConfig,SynConfig}
import scala.util.parsing.combinator.syntactical.StandardTokenParsers
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer


class SSLParser (config: SynConfig = defaultConfig) extends StandardTokenParsers with SepLogicUtils {

  // Modified repN
  def repAll[T](p: => Parser[T]): Parser[List[T]] =
    Parser { in =>
      val elems = new ListBuffer[T]
      val p0 = p    // avoid repeatedly re-evaluating by-name parser

      @tailrec def applyp(in0: Input): ParseResult[List[T]] =
        if (in0.atEnd) Success(elems.toList, in0)
        else p0(in0) match {
          case Success(x, rest) => elems += x ; applyp(rest)
          case ns: NoSuccess    => ns
        }

      applyp(in)
    }

  override val lexical = new SSLLexical

  def typeParser: Parser[SSLType] =
    ("int" ^^^ IntType
        | "bool" ^^^ BoolType
        | "loc" ^^^ LocType
        | "set" ^^^ IntSetType
        | "void" ^^^ VoidType)

  def formal: Parser[(SSLType, Var)] = typeParser ~ ident ^^ { case a ~ b => (a, Var(b)) }

  def intLiteral: Parser[Const] =
    numericLit ^^ (x => IntConst(Integer.parseInt(x)))

  def boolLiteral: Parser[Const] =
    ("true" | "false") ^^ (b => BoolConst(java.lang.Boolean.parseBoolean(b)))

  def setLiteral: Parser[Expr] =
    "{" ~> repsep(expr, ",") <~ "}" ^^ SetLiteral

  def varParser: Parser[Var] = ident ^^ Var

  def unOpParser: Parser[UnOp] =
    "not" ^^^ OpNot

  def termOpParser: Parser[OverloadedBinOp] = "+" ^^^ OpPlus ||| "-" ^^^ OpMinus ||| "++" ^^^ OpUnion ||| "--" ^^^ OpDiff

  def relOpParser: Parser[OverloadedBinOp] = "<=" ^^^ OpLeq ||| "<" ^^^ OpLt ||| "==" ^^^ OpOverloadedEq ||| "=i" ^^^ OpOverloadedEq ||| "<=i" ^^^ OpSubset ||| "in" ^^^ OpIn

  def logOpParser: Parser[OverloadedBinOp] = ("\\/"|"||") ^^^ OpOr ||| ("/\\"|"&&") ^^^ OpAnd

  def binOpParser(p: Parser[OverloadedBinOp]): Parser[(Expr, Expr) => Expr] = {
    p ^^ { op => (l, r) => OverloadedBinaryExpr(op, l, r) }
  }

  def atom: Parser[Expr] = (
      unOpParser ~ atom ^^ { case op ~ a => UnaryExpr(op, a) }
          | "(" ~> expr <~ ")"
          | intLiteral | boolLiteral | setLiteral
          | varParser
      )

  def term: Parser[Expr] = chainl1(atom, binOpParser(termOpParser))

  def relExpr: Parser[Expr] =
    term ~ opt(relOpParser ~ term) ^^ {
      case a ~ None => a
      case a ~ Some(op ~ b) => OverloadedBinaryExpr(op, a, b)
    }

  def expr: Parser[Expr] =
    chainl1(relExpr, binOpParser(logOpParser)) ~ opt(("?" ~> expr <~ ":") ~ expr) ^^ {
      case a ~ None => a
      case a ~ Some(l ~ r) => IfThenElse(a, l, r)
    }

  def identWithOffset: Parser[(Ident, Int)] = {
    val ov = ident ~ opt("+" ~> numericLit)
    ("(" ~> ov <~ ")" | ov) ^^ { case i ~ o =>
      val off = Math.max(Integer.parseInt(o.getOrElse("0")), 0)
      (i, off)
    }
  }

  def immutableheaplet : Parser[Heaplet] = (
    ("[" ~> heaplet(Mut) <~ ("]" ~ "@")) ~ numericLit ^^ { case h ~ n => h.makeUnknown(Integer.parseInt(n)) } // later change permission
    ||| ("[" ~> heaplet(Mut) <~ ("]" ~ "@")) ~ perm ^^   { case h ~ m => h.setMut(m)(config) }// TODO [Immutability] get rid of the bare I eventually
    ||| heaplet(Mut)
  )

  def heaplet(mutable : MTag): Parser[Heaplet] = (
      (identWithOffset <~ ":->") ~ expr ^^                   { case (a, o) ~ b => PointsTo(Var(a), o, b).setMut(mutable)(config) }
          ||| "[" ~> (ident ~ ("," ~> numericLit)) <~ "]" ^^ { case a ~ s => Block(Var(a), Integer.parseInt(s)).setMut(mutable)(config)}
          ||| ident ~ ("(" ~> rep1sep(expr, ",") <~ ")") ~ opt("[" ~> rep1sep(perm, ",") <~ "]") ^^
                                                             { case name ~ args ~ perms => SApp(name, args).setMut(mutable,perms)(config)}
  )

  def perm : Parser[MTag] = (
    "M" ^^^ Mut
      ||| (("I" ~ "@") ~> ident) ^^ (s => ImmVar(Var(s)))
      ||| ("I" ~ "@" ~ "M") ^^ (p => Imm(Mut))
      ||| numericLit ^^ (n => U(Integer.parseInt(n)))
  )

  def sigma: Parser[SFormula] = (
      "emp" ^^^ SFormula(Nil)
          ||| repsep(immutableheaplet, "**") ^^ { hs => SFormula(hs) }
      )

  def assertion: Parser[Assertion] = "{" ~> (opt(expr <~ ";") ~ sigma) <~ "}" ^^ {
    case Some(p) ~ s => Assertion(p, s)
    case None ~ s => Assertion(pTrue, s)
  }

  def indClause: Parser[InductiveClause] =
    expr ~ ("=>" ~> assertion) ^^ { case p ~ a => InductiveClause(p, a) }

  // TODO [Immutability] enforce list somewhere i.e. make the numbers match up with inputs
  def indPredicate: Parser[InductivePredicate] =
    ("predicate" ~> ident) ~ ("(" ~> repsep(formal, ",") <~ ")") ~ (opt("[" ~> repsep(perm, ",") <~ "]")) ~
        (("{" ~ opt("|")) ~> rep1sep(indClause, "|") <~ "}") ^^ {
      case name ~ formals ~ perms ~ clauses => InductivePredicate(name, formals, clauses)
    }

  def uGoal: Parser[UnificationGoal] = ("(" ~> rep1sep(varParser, ",") <~ ")") ~ assertion ^^ {
    case params ~ formula => UnificationGoal(formula, params.toSet)
  }

  def goalFunction: Parser[FunSpec] = assertion ~ typeParser ~ ident ~ ("(" ~> repsep(formal, ",") <~ ")") ~ assertion ^^ {
    case pre ~ tpe ~ name ~ formals ~ post => FunSpec(name, tpe, formals, pre, post)
  }

  def program: Parser[Program] = repAll(indPredicate | goalFunction) ^^ { pfs =>
    val ps = for (p@InductivePredicate(_, _, _) <- pfs) yield p
    val fs = for (f@FunSpec(_, _, _, _, _) <- pfs) yield f
    if (fs.isEmpty){
      throw SynthesisException("Parsing failed. No single function spec is provided.")
    }
    val goal = fs.last
    val funs = fs.take(fs.length - 1)
    Program(ps, funs, goal)
  }

  def parse[T](p: Parser[T])(input: String): ParseResult[T] = p(new lexical.Scanner(input)) match {
    case e: Error => Failure(e.msg, e.next)
    case Success(_, in) if !in.atEnd => Failure("Not fully parsed", in)
    case s => s
  }

  def parseUnificationGoal(input: String): ParseResult[UnificationGoal] = parse(uGoal)(input)

  def parseGoal(input: String): ParseResult[Program] = parse(program)(input)

}

