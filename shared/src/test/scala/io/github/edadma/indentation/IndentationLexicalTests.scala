package io.github.edadma.indentation

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.util.parsing.input.CharSequenceReader

class IndentationLexicalTests extends AnyFreeSpec with Matchers {

  val lexical = new IndentationLexical(
    newlineBeforeIndent = false,
    newlineAfterDedent = true,
    startLineJoining = List("[", "("),
    endLineJoining = List("]", ")"),
    lineComment = ";;",
    blockCommentStart = "/*",
    blockCommentEnd = "*/",
  ) {
    // Add delimiters needed for testing
    delimiters ++= List("+", "*", "-", "/", "^", "(", ")", "[", "]", ",", "=", "==", "/=", "<", ">", "<=", ">=")
  }

  import lexical.{Newline => nl, Indent => ind, Dedent => ded}

  // Helper methods to create tokens
  def num(s: String) = lexical.NumericLit(s)
  def id(s: String)  = lexical.Identifier(s)
  def kw(s: String)  = lexical.Keyword(s)
  def str(s: String) = lexical.StringLit(s)

  "basic newline handling" - {
    "single number" in {
      lexical.scan("1") shouldBe List(num("1"), nl)
    }

    "two numbers on separate lines" in {
      lexical.scan("1\n2") shouldBe List(num("1"), nl, num("2"), nl)
    }

    "trailing newline" in {
      lexical.scan("1\n") shouldBe List(num("1"), nl)
    }

    "leading newline" in {
      lexical.scan("\n1") shouldBe List(num("1"), nl)
    }

    "multiple newlines" in {
      lexical.scan("1\n\n2") shouldBe List(num("1"), nl, num("2"), nl)
    }

    "windows line endings" in {
      lexical.scan("1\r\n2") shouldBe List(num("1"), nl, num("2"), nl)
      lexical.scan("1\r\n") shouldBe List(num("1"), nl)
      lexical.scan("1\r\n2\r\n") shouldBe List(num("1"), nl, num("2"), nl)
      lexical.scan("\r\n1") shouldBe List(num("1"), nl)
      lexical.scan("\r\n1\r\n2") shouldBe List(num("1"), nl, num("2"), nl)
      lexical.scan("\r\n1\r\n") shouldBe List(num("1"), nl)
      lexical.scan("\r\n1\r\n2\r\n") shouldBe List(num("1"), nl, num("2"), nl)
    }
  }

  "indentation handling" - {
    "simple tab indent" in {
      lexical.scan("1\n\t2") shouldBe List(num("1"), ind, num("2"), nl, ded, nl)
    }

    "simple space indent" in {
      lexical.scan("1\n  2") shouldBe List(num("1"), ind, num("2"), nl, ded, nl)
    }

    "multiple indent levels with spaces" in {
      lexical.scan("1\n  2\n    3") shouldBe List(num("1"), ind, num("2"), ind, num("3"), nl, ded, nl, ded, nl)
    }

    "dedent to original level" in {
      lexical.scan("1\n  2\n3") shouldBe List(num("1"), ind, num("2"), nl, ded, nl, num("3"), nl)
    }

    "multiple dedents" in {
      lexical.scan("1\n  2\n    3\n4") shouldBe List(
        num("1"),
        ind,
        num("2"),
        ind,
        num("3"),
        nl,
        ded,
        nl,
        ded,
        nl,
        num("4"),
        nl,
      )
    }

    "indentation at start" in {
      lexical.scan("\t1\n") shouldBe List(num("1"), nl)
      lexical.scan("\t1\n2\n") shouldBe List(num("1"), nl, num("2"), nl)
    }

    "complex indentation pattern" in {
      val input = """1
                      |  2
                      |    3
                      |  4
                      |5""".stripMargin

      val tokens      = lexical.scan(input)
      val indentCount = tokens.count(_ == ind)
      val dedentCount = tokens.count(_ == ded)

      // Should have balanced indents and dedents
      indentCount shouldBe dedentCount
      tokens should contain(num("1"))
      tokens should contain(num("2"))
      tokens should contain(num("3"))
      tokens should contain(num("4"))
      tokens should contain(num("5"))
    }
  }

  "line joining with parentheses and brackets" - {
    "simple parentheses" in {
      val tokens = lexical.scan("f(1, 2)")
      tokens should contain(id("f"))
      tokens should contain(kw("("))
      tokens should contain(num("1"))
      tokens should contain(kw(","))
      tokens should contain(num("2"))
      tokens should contain(kw(")"))
      // Should not have indentation tokens due to line joining
      tokens should not contain ind
      tokens should not contain ded
    }

    "parentheses across lines" in {
      val tokens = lexical.scan("f(\n  1,\n  2\n)")
      tokens should contain(id("f"))
      tokens should contain(num("1"))
      tokens should contain(num("2"))
      // Should not have indentation tokens due to line joining
      tokens should not contain ind
      tokens should not contain ded
    }

    "brackets across lines" in {
      val tokens = lexical.scan("[\n  1,\n  2\n]")
      tokens should contain(kw("["))
      tokens should contain(num("1"))
      tokens should contain(num("2"))
      tokens should contain(kw("]"))
      // Should not have indentation tokens due to line joining
      tokens should not contain ind
      tokens should not contain ded
    }

    "nested line joining" in {
      val tokens = lexical.scan("f([\n  1,\n  2\n])")
      tokens should contain(id("f"))
      tokens should contain(kw("("))
      tokens should contain(kw("["))
      tokens should contain(num("1"))
      tokens should contain(num("2"))
      tokens should contain(kw("]"))
      tokens should contain(kw(")"))
      tokens should not contain ind
      tokens should not contain ded
    }
  }

  "mixed content" - {
    "identifiers and operators" in {
      val tokens = lexical.scan("x = 1 + 2")
      tokens should contain(id("x"))
      tokens should contain(kw("="))
      tokens should contain(num("1"))
      tokens should contain(kw("+"))
      tokens should contain(num("2"))
    }

    "string literals" in {
      val tokens = lexical.scan(""""hello world"""")
      tokens should contain(str("hello world"))
    }

    "complex expression with indentation" in {
      val input = """x = 1
                      |if x > 0
                      |  print "positive"
                      |  y = x * 2
                      |print "done"""".stripMargin

      val tokens = lexical.scan(input)

      // Should have balanced indents/dedents
      val indentCount = tokens.count(_ == ind)
      val dedentCount = tokens.count(_ == ded)
      indentCount shouldEqual dedentCount

      // Should contain our identifiers
      tokens should contain(id("x"))
      tokens should contain(id("y"))
      tokens should contain(id("print"))
    }
  }

  "property-based tests" - {
    "balanced indents and dedents" in {
      val depths = List(0, 1, 2, 3, 4, 5)
      for (depth <- depths) {
        val indent = "  " * depth
        val code   = s"1\n${indent}2"
        val tokens = lexical.scan(code)

        val indentCount = tokens.count(_ == ind)
        val dedentCount = tokens.count(_ == ded)

        if (depth > 0) {
          indentCount shouldBe 1
          dedentCount shouldBe 1
        } else {
          indentCount shouldBe 0
          dedentCount shouldBe 0
        }
      }
    }

    "always ends with newline" in {
      val testCases = List("1", "abc", "1\n2", "hello world")
      for (s <- testCases) {
        val tokens = lexical.scan(s)
        if (tokens.nonEmpty) {
          tokens.last shouldBe nl
        }
      }
    }
  }

  "edge cases" - {
    "empty input" in {
      lexical.scan("") shouldBe List(nl)
    }

    "only whitespace" in {
      lexical.scan("   ") shouldBe List(nl)
      lexical.scan("   \n  \n") shouldBe List(nl)
    }

    "mixed tabs and spaces - should handle gracefully" in {
      // Your implementation might handle this differently, but shouldn't crash
      noException should be thrownBy lexical.scan("1\n\t 2")
    }
  }

  "error handling" - {
    "should handle unknown tokens gracefully" in {
      // The lexical analyzer may produce error tokens for unknown characters
      noException should be thrownBy lexical.scan("1 @ 2")
    }
  }

  "reader interface" - {
    "should work with Reader[Char] input" in {
      val reader = lexical.read(new CharSequenceReader("1\n  2"))
      reader.atEnd shouldBe false
      reader.first shouldBe num("1")

      val rest1 = reader.rest
      rest1.first shouldBe ind

      val rest2 = rest1.rest
      rest2.first shouldBe num("2")
    }

    "should handle atEnd correctly" in {
      val reader = lexical.read(new CharSequenceReader("1"))
      reader.atEnd shouldBe false

      // Navigate to the end
      var current = reader
      while (!current.atEnd) {
        current = current.rest
      }
      current.atEnd shouldBe true
    }
  }
}
