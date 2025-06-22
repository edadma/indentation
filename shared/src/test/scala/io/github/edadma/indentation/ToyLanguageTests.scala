package io.github.edadma.indentation

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ToyLanguageTests extends AnyFreeSpec with Matchers {

  def captureOutput(block: => Unit): String = {
    val outputStream = new ByteArrayOutputStream()
    val printStream  = new PrintStream(outputStream)
    val originalOut  = System.out

    try {
      System.setOut(printStream)
      block
      outputStream.toString().trim()
    } finally {
      System.setOut(originalOut)
    }
  }

  "simple expressions" - {
    "basic assignment" in {
      val program = """x = 5"""
      ToyLanguageParser.parse(program) should matchPattern {
        case ToyLanguageParser.Success(List(Assign("x", Num(5))), _) =>
      }
    }

    "arithmetic operations" in {
      val program = """result = 2 + 3 * 4"""
      ToyLanguageParser.parse(program) should matchPattern {
        case ToyLanguageParser.Success(List(Assign("result", BinOp(Num(2), "+", BinOp(Num(3), "*", Num(4))))), _) =>
      }
    }

    "parentheses" in {
      val program = """result = (2 + 3) * 4"""
      ToyLanguageParser.parse(program) should matchPattern {
        case ToyLanguageParser.Success(List(Assign("result", BinOp(BinOp(Num(2), "+", Num(3)), "*", Num(4)))), _) =>
      }
    }
  }

  "print statements" - {
    "print number" in {
      val program = """print 42"""
      ToyLanguageParser.parse(program) should matchPattern {
        case ToyLanguageParser.Success(List(Print(Num(42))), _) =>
      }
    }

    "print variable" in {
      val program = """print x"""
      ToyLanguageParser.parse(program) should matchPattern {
        case ToyLanguageParser.Success(List(Print(Var("x"))), _) =>
      }
    }
  }

  "simple if statements" - {
    "basic if without else" in {
      val program = "if x > 0 then\n    print x"

      ToyLanguageParser.parse(program) should matchPattern {
        case ToyLanguageParser.Success(List(If(BinOp(Var("x"), ">", Num(0)), List(Print(Var("x"))), None)), _) =>
      }
    }

    "if with else" in {
      val program = "if x > 0 then\n    print x\nelse\n    print 0"

      ToyLanguageParser.parse(program) should matchPattern {
        case ToyLanguageParser.Success(
              List(If(BinOp(Var("x"), ">", Num(0)), List(Print(Var("x"))), Some(List(Print(Num(0)))))),
              _,
            ) =>
      }
    }
  }

  "indentation tests" - {
    "debug token stream" in {
      val program = "if x > 0 then\n    print x"
      val tokens  = ToyLanguageParser.lexical.scan(program)
      println(s"Tokens for '$program': $tokens")
      // This test is just for debugging - it will always pass
      tokens.length should be > 0
    }

    "debug nested if token stream" in {
      val program = "if x > 0 then\n    if x > 10 then\n        print 1\n    else\n        print 2\nelse\n    print 3"
      val tokens  = ToyLanguageParser.lexical.scan(program)
      println(s"Nested tokens: $tokens")
      // Count indents and dedents
      val indentCount = tokens.count(_ == ToyLanguageParser.lexical.Indent)
      val dedentCount = tokens.count(_ == ToyLanguageParser.lexical.Dedent)
      println(s"Indents: $indentCount, Dedents: $dedentCount")
      tokens.length should be > 0
    }

    "nested if statements" in {
      val program = "if x > 0 then\n    if x > 10 then\n        print 1\n    else\n        print 2\nelse\n    print 3"

      val result = ToyLanguageParser.parse(program)
      result should matchPattern { case ToyLanguageParser.Success(_, _) => }

      // Verify structure
      result match {
        case ToyLanguageParser.Success(List(If(_, thenBlock, Some(elseBlock))), _) =>
          thenBlock should have length 1
          thenBlock.head should matchPattern { case If(_, _, _) => }
          elseBlock should have length 1
        case _ => fail("Expected successful parse with nested if")
      }
    }

    "multiple statements in blocks" in {
      val program = "if x > 0 then\n    y = x + 1\n    z = y * 2\n    print z\nprint y"

      val result = ToyLanguageParser.parse(program)
      result should matchPattern { case ToyLanguageParser.Success(_, _) => }

      result match {
        case ToyLanguageParser.Success(stmts, _) =>
          stmts should have length 2
          stmts.head should matchPattern { case If(_, List(_, _, _), None) => }
        case _ => fail("Expected successful parse")
      }
    }

    "deep nesting" in {
      val program =
        "if a > 0 then\n    if b > 0 then\n        if c > 0 then\n            print 1\n        print 2\n    print 3\nprint 4"

      val result = ToyLanguageParser.parse(program)
      result should matchPattern { case ToyLanguageParser.Success(List(_, Print(Num(4))), _) => }
    }
  }

  "interpreter tests" - {
    "basic assignment and print" in {
      val program = "x = 42\nprint x"

      val parseResult = ToyLanguageParser.parse(program)
      parseResult should matchPattern { case ToyLanguageParser.Success(_, _) => }

      val output = captureOutput {
        parseResult match {
          case ToyLanguageParser.Success(ast, _) => ToyInterpreter.run(ast)
          case _                                 => fail("Parse failed")
        }
      }
      output shouldBe "42"
    }

    "arithmetic operations" in {
      val program = "x = 2 + 3 * 4\nprint x"

      val output = captureOutput {
        ToyLanguageParser.parse(program) match {
          case ToyLanguageParser.Success(ast, _) => ToyInterpreter.run(ast)
          case failure                           => fail(s"Parse failed: $failure")
        }
      }
      output shouldBe "14" // 2 + (3 * 4)
    }

    "conditional execution" in {
      val program = "x = 5\nif x > 3 then\n    print 1\nelse\n    print 0"

      val output = captureOutput {
        ToyLanguageParser.parse(program) match {
          case ToyLanguageParser.Success(ast, _) => ToyInterpreter.run(ast)
          case failure                           => fail(s"Parse failed: $failure")
        }
      }
      output shouldBe "1"
    }

    "nested conditions" in {
      val program =
        "x = 15\nif x > 10 then\n    if x > 20 then\n        print 3\n    else\n        print 2\nelse\n    print 1"

      val output = captureOutput {
        ToyLanguageParser.parse(program) match {
          case ToyLanguageParser.Success(ast, _) => ToyInterpreter.run(ast)
          case failure                           => fail(s"Parse failed: $failure")
        }
      }
      output shouldBe "2"
    }

    "complex program" in {
      val program =
        "a = 1\nb = 2\nif a < b then\n    sum = a + b\n    product = a * b\n    if sum > product then\n        print sum\n    else\n        print product\nelse\n    print 0"

      val output = captureOutput {
        ToyLanguageParser.parse(program) match {
          case ToyLanguageParser.Success(ast, _) => ToyInterpreter.run(ast)
          case failure                           => fail(s"Parse failed: $failure")
        }
      }
      output shouldBe "3" // sum (1+2) > product (1*2)
    }
  }

  "line joining tests" - {
    "parentheses across lines" in {
      val program = "result = (1 +\n          2 +\n          3)\nprint result"

      val output = captureOutput {
        ToyLanguageParser.parse(program) match {
          case ToyLanguageParser.Success(ast, _) => ToyInterpreter.run(ast)
          case failure                           => fail(s"Parse failed: $failure")
        }
      }
      output shouldBe "6"
    }
  }

  "error cases" - {
    "syntax error" in {
      val program = "x = \nprint x"

      ToyLanguageParser.parse(program) should matchPattern {
        case ToyLanguageParser.NoSuccess(_, _) =>
      }
    }

    "missing indent" in {
      val program = "if x > 0 then\nprint x"

      ToyLanguageParser.parse(program) should matchPattern {
        case ToyLanguageParser.NoSuccess(_, _) =>
      }
    }
  }
}
