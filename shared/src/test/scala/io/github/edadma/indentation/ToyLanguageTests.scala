package io.github.edadma.indentation

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class ToyLanguageTests extends AnyFreeSpec with Matchers {

  def parse(input: String) = ToyLanguageParser.parse(input)

  def runProgram(input: String): List[Int] = {
    val buf = scala.collection.mutable.ListBuffer[Int]()
    parse(input) match {
      case ToyLanguageParser.Success(ast, _) =>
        new ToyInterpreter(v => buf += v).run(ast)
        buf.toList
      case failure => fail(s"Parse failed: $failure")
    }
  }

  "simple expressions" - {
    "basic assignment" in {
      parse("x = 5") should matchPattern {
        case ToyLanguageParser.Success(List(Assign("x", Num(5))), _) =>
      }
    }

    "arithmetic operations" in {
      parse("result = 2 + 3 * 4") should matchPattern {
        case ToyLanguageParser.Success(List(Assign("result", BinOp(Num(2), "+", BinOp(Num(3), "*", Num(4))))), _) =>
      }
    }

    "parentheses" in {
      parse("result = (2 + 3) * 4") should matchPattern {
        case ToyLanguageParser.Success(List(Assign("result", BinOp(BinOp(Num(2), "+", Num(3)), "*", Num(4)))), _) =>
      }
    }
  }

  "print statements" - {
    "print number" in {
      parse("print 42") should matchPattern {
        case ToyLanguageParser.Success(List(Print(Num(42))), _) =>
      }
    }

    "print variable" in {
      parse("print x") should matchPattern {
        case ToyLanguageParser.Success(List(Print(Var("x"))), _) =>
      }
    }
  }

  "simple if statements" - {
    "basic if without else" in {
      val result = parse("if x > 0 then\n    print x")
      result should matchPattern {
        case ToyLanguageParser.Success(List(If(BinOp(Var("x"), ">", Num(0)), List(Print(Var("x"))), None)), _) =>
      }
    }

    "if with else" in {
      val result = parse("if x > 0 then\n    print x\nelse\n    print 0")
      result should matchPattern {
        case ToyLanguageParser.Success(
              List(If(BinOp(Var("x"), ">", Num(0)), List(Print(Var("x"))), Some(List(Print(Num(0)))))),
              _,
            ) =>
      }
    }
  }

  "indentation tests" - {
    "nested if statements" in {
      val result = parse("if x > 0 then\n    if x > 10 then\n        print 1\n    else\n        print 2\nelse\n    print 3")
      result should matchPattern { case ToyLanguageParser.Success(_, _) => }

      result match {
        case ToyLanguageParser.Success(List(If(_, thenBlock, Some(elseBlock))), _) =>
          thenBlock should have length 1
          thenBlock.head should matchPattern { case If(_, _, _) => }
          elseBlock should have length 1
        case _ => fail("Expected successful parse with nested if")
      }
    }

    "multiple statements in blocks" in {
      val result = parse("if x > 0 then\n    y = x + 1\n    z = y * 2\n    print z\nprint y")
      result should matchPattern { case ToyLanguageParser.Success(_, _) => }

      result match {
        case ToyLanguageParser.Success(stmts, _) =>
          stmts should have length 2
          stmts.head should matchPattern { case If(_, List(_, _, _), None) => }
        case _ => fail("Expected successful parse")
      }
    }

    "deep nesting" in {
      val result = parse(
        "if a > 0 then\n    if b > 0 then\n        if c > 0 then\n            print 1\n        print 2\n    print 3\nprint 4")
      result should matchPattern { case ToyLanguageParser.Success(List(_, Print(Num(4))), _) => }
    }
  }

  "interpreter tests" - {
    "basic assignment and print" in {
      runProgram("x = 42\nprint x") shouldBe List(42)
    }

    "arithmetic operations" in {
      runProgram("x = 2 + 3 * 4\nprint x") shouldBe List(14)
    }

    "conditional execution" in {
      runProgram("x = 5\nif x > 3 then\n    print 1\nelse\n    print 0") shouldBe List(1)
    }

    "nested conditions" in {
      runProgram(
        "x = 15\nif x > 10 then\n    if x > 20 then\n        print 3\n    else\n        print 2\nelse\n    print 1") shouldBe List(2)
    }

    "complex program" in {
      runProgram(
        "a = 1\nb = 2\nif a < b then\n    sum = a + b\n    product = a * b\n    if sum > product then\n        print sum\n    else\n        print product\nelse\n    print 0") shouldBe List(3)
    }
  }

  "line joining tests" - {
    "parentheses across lines" in {
      runProgram("result = (1 +\n          2 +\n          3)\nprint result") shouldBe List(6)
    }
  }

  "error cases" - {
    "syntax error" in {
      parse("x = \nprint x") should matchPattern {
        case ToyLanguageParser.NoSuccess(_, _) =>
      }
    }

    "missing indent" in {
      parse("if x > 0 then\nprint x") should matchPattern {
        case ToyLanguageParser.NoSuccess(_, _) =>
      }
    }
  }
}
