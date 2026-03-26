package io.github.edadma.indentation

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.util.parsing.combinator.syntactical.StandardTokenParsers
import scala.util.parsing.input.CharSequenceReader

class SimpleParser extends StandardTokenParsers {
  override val lexical: IndentationLexical = new IndentationLexical(
    newlineBeforeIndent = true,
    newlineAfterDedent = true,
    startLineJoining = List("("),
    endLineJoining = List(")"),
    lineComment = "//",
    blockCommentStart = "/*",
    blockCommentEnd = "*/",
  ) {
    reserved ++= List("if", "then", "print")
    delimiters ++= List("=", "+", "-", "*", "/", "<", ">", "(", ")")
  }

  import lexical.{Newline, Indent, Dedent}

  lazy val program: Parser[List[String]] =
    repsep(stmt, rep1(Newline)) <~ opt(rep(Newline))

  lazy val stmt: Parser[String] =
    "if" ~> ident ~ ("then" ~> block) ^^ { case id ~ stmts => s"if($id)" } |
      "print" ~> ident ^^ (id => s"print($id)") |
      ident ~ ("=" ~> ident) ^^ { case a ~ b => s"$a=$b" }

  lazy val block: Parser[List[String]] =
    Newline ~> Indent ~> repsep(stmt, rep1(Newline)) <~ opt(Newline) <~ Dedent

  def parseInput(input: String): ParseResult[List[String]] =
    phrase(program)(lexical.read(new CharSequenceReader(input)))
}

class ReentrancyTests extends AnyFreeSpec with Matchers {

  "sequential parses with fresh instances" in {
    val r1 = (new SimpleParser).parseInput("x = y")
    r1.successful shouldBe true

    val r2 = (new SimpleParser).parseInput("a = b")
    r2.successful shouldBe true
  }

  "sequential parses with indentation" in {
    val r1 = (new SimpleParser).parseInput("if x then\n    print y")
    r1.successful shouldBe true

    val r2 = (new SimpleParser).parseInput("if a then\n    print b")
    r2.successful shouldBe true
  }

  "many sequential parses" in {
    for _ <- 1 to 20 do
      (new SimpleParser).parseInput("x = y").successful shouldBe true
  }

  "many sequential parses with blocks" in {
    for _ <- 1 to 20 do
      (new SimpleParser).parseInput("if x then\n    print y").successful shouldBe true
  }

  "alternating simple and block parses" in {
    for _ <- 1 to 10 do
      (new SimpleParser).parseInput("x = y").successful shouldBe true
      (new SimpleParser).parseInput("if x then\n    print y").successful shouldBe true
  }

  "sequential scan calls on same lexer" in {
    val lex = new IndentationLexical(
      newlineBeforeIndent = true, newlineAfterDedent = true,
      startLineJoining = Nil, endLineJoining = Nil,
      lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
    ) {
      delimiters ++= List("=", "+")
    }

    val t1 = lex.scan("x = 1")
    t1 should not be empty

    val t2 = lex.scan("y = 2")
    t2 should not be empty
  }

  "sequential scan calls with indentation on same lexer" in {
    val lex = new IndentationLexical(
      newlineBeforeIndent = false, newlineAfterDedent = true,
      startLineJoining = Nil, endLineJoining = Nil,
      lineComment = "//", blockCommentStart = "/*", blockCommentEnd = "*/",
    ) {
      delimiters ++= List("=")
    }

    val t1 = lex.scan("a\n  b\nc")
    val t2 = lex.scan("x\n  y\nz")

    t1.count(_.chars == "indent") shouldBe t1.count(_.chars == "dedent")
    t2.count(_.chars == "indent") shouldBe t2.count(_.chars == "dedent")
  }
}
