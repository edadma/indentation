package io.github.edadma.indentation

import scala.util.parsing.combinator.PackratParsers
import scala.util.parsing.combinator.syntactical.StandardTokenParsers
import scala.util.parsing.input.Reader

// AST nodes
sealed trait Stmt
case class Assign(variable: String, value: Expr)                                            extends Stmt
case class If(condition: Expr, thenBlock: List[Stmt], elseBlock: Option[List[Stmt]] = None) extends Stmt
case class Print(expr: Expr)                                                                extends Stmt

sealed trait Expr
case class Var(name: String)                          extends Expr
case class Num(value: Int)                            extends Expr
case class BinOp(left: Expr, op: String, right: Expr) extends Expr

object ToyLanguageParser extends StandardTokenParsers with PackratParsers {

  override val lexical: IndentationLexical = new IndentationLexical(
    newlineBeforeIndent = true, // Changed to true!
    newlineAfterDedent = true,
    startLineJoining = List("("),
    endLineJoining = List(")"),
    lineComment = "//",
    blockCommentStart = "/*",
    blockCommentEnd = "*/",
  ) {
    // Add keywords
    reserved ++= List("if", "else", "then", "print")

    // Add operators and delimiters
    delimiters ++= List("=", "+", "-", "*", "/", "<", ">", "==", "!=", "<=", ">=", "(", ")")
  }

  def parse(input: Reader[Char]): ParseResult[List[Stmt]] = {
    phrase(program)(lexical.read(input))
  }

  def parse(input: String): ParseResult[List[Stmt]] = {
    phrase(program)(lexical.read(new scala.util.parsing.input.CharSequenceReader(input)))
  }

  import lexical.{Newline, Indent, Dedent}

  // Grammar rules
  lazy val program: PackratParser[List[Stmt]] =
    rep(statement) <~ opt(Newline)

  lazy val statement: PackratParser[Stmt] =
    assignment |
      ifStatement |
      printStatement

  lazy val assignment: PackratParser[Assign] =
    ident ~ "=" ~ expr <~ Newline ^^ { case id ~ _ ~ value => Assign(id, value) }

  lazy val ifStatement: PackratParser[If] =
    "if" ~ expr ~ "then" ~ Newline ~ Indent ~ rep1(statement) ~ Dedent ~ opt(elseClause) ^^ {
      case _ ~ condition ~ _ ~ _ ~ _ ~ thenStmts ~ _ ~ elseStmts =>
        If(condition, thenStmts, elseStmts)
    }

  lazy val elseClause: PackratParser[List[Stmt]] =
    opt(Newline) ~ "else" ~ Newline ~ Indent ~ rep1(statement) ~ Dedent ^^ {
      case _ ~ _ ~ _ ~ _ ~ elseStmts ~ _ => elseStmts
    }

  lazy val printStatement: PackratParser[Print] =
    "print" ~ expr <~ Newline ^^ { case _ ~ value => Print(value) }

  // Expression parsing with left recursion (thanks to packrat!)
  lazy val expr: PackratParser[Expr] =
    comparison

  lazy val comparison: PackratParser[Expr] =
    comparison ~ ("==" | "!=" | "<=" | ">=" | "<" | ">") ~ addition ^^ {
      case left ~ op ~ right => BinOp(left, op, right)
    } |
      addition

  lazy val addition: PackratParser[Expr] =
    addition ~ ("+" | "-") ~ multiplication ^^ {
      case left ~ op ~ right => BinOp(left, op, right)
    } |
      multiplication

  lazy val multiplication: PackratParser[Expr] =
    multiplication ~ ("*" | "/") ~ primary ^^ {
      case left ~ op ~ right => BinOp(left, op, right)
    } |
      primary

  lazy val primary: PackratParser[Expr] =
    numericLit ^^ { n => Num(n.toInt) } |
      ident ^^ { id => Var(id) } |
      "(" ~> expr <~ ")"
}

// Simple interpreter for testing
object ToyInterpreter {
  type Env = scala.collection.mutable.Map[String, Int]

  def run(program: List[Stmt]): Unit = {
    val env: Env = scala.collection.mutable.Map()
    program.foreach(executeStatement(_, env))
  }

  def executeStatement(stmt: Stmt, env: Env): Unit = stmt match {
    case Assign(variable, value) =>
      env(variable) = evaluateExpr(value, env)

    case If(condition, thenBlock, elseBlock) =>
      val condValue = evaluateExpr(condition, env)
      if (condValue != 0) {
        thenBlock.foreach(executeStatement(_, env))
      } else {
        elseBlock.foreach(_.foreach(executeStatement(_, env)))
      }

    case Print(expr) =>
      val value = evaluateExpr(expr, env)
      println(value)
  }

  def evaluateExpr(expr: Expr, env: Env): Int = expr match {
    case Var(name) =>
      env.getOrElse(name, throw new RuntimeException(s"Undefined variable: $name"))

    case Num(value) =>
      value

    case BinOp(left, op, right) =>
      val leftVal  = evaluateExpr(left, env)
      val rightVal = evaluateExpr(right, env)
      op match {
        case "+"  => leftVal + rightVal
        case "-"  => leftVal - rightVal
        case "*"  => leftVal * rightVal
        case "/"  => leftVal / rightVal
        case "==" => if (leftVal == rightVal) 1 else 0
        case "!=" => if (leftVal != rightVal) 1 else 0
        case "<"  => if (leftVal < rightVal) 1 else 0
        case ">"  => if (leftVal > rightVal) 1 else 0
        case "<=" => if (leftVal <= rightVal) 1 else 0
        case ">=" => if (leftVal >= rightVal) 1 else 0
        case _    => throw new RuntimeException(s"Unknown operator: $op")
      }
  }
}
