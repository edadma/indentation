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

  // Comments are scanned by two separate code paths: one for a comment at the
  // start of a line (`skipBlankLines`), one for a comment in the middle of a line
  // (`whitespace`). Every case below is exercised in both positions, because a
  // defect in either path is invisible from the other.
  "block comments" - {
    def err(msg: String) = lexical.ErrorToken(msg)

    /** Token positions, which plain `scan` discards. */
    def scanWithPos(s: String): List[(lexical.Token, Int, Int)] = {
      var r   = lexical.read(new CharSequenceReader(s))
      val buf = List.newBuilder[(lexical.Token, Int, Int)]

      while (!r.atEnd) {
        buf += ((r.first, r.pos.line, r.pos.column))
        r = r.rest
      }

      buf.result()
    }

    "nesting" - {
      "one level, at start of line" in {
        lexical.scan("/* outer /* inner */ still outer */\n1") shouldBe List(num("1"), nl)
      }

      "one level, mid-line" in {
        lexical.scan("1 /* outer /* inner */ still outer */ 2") shouldBe List(num("1"), num("2"), nl)
      }

      "several levels, at start of line" in {
        lexical.scan("/* a /* b /* c */ d */ e */\n1") shouldBe List(num("1"), nl)
      }

      "several levels, mid-line" in {
        lexical.scan("1 /* a /* b /* c */ d */ e */ 2") shouldBe List(num("1"), num("2"), nl)
      }

      "a nested comment spanning lines, at start of line" in {
        lexical.scan("/* a\n   /* b\n   */\n*/\n1") shouldBe List(num("1"), nl)
      }

      "a nested comment spanning lines, mid-line" in {
        lexical.scan("1 /* a\n   /* b\n   */\n*/ 2") shouldBe List(num("1"), num("2"), nl)
      }

      "an inner comment does not close the outer one early, at start of line" in {
        // Without nesting this leaves `still outer */` to be lexed as code.
        lexical.scan("/* outer /* inner */ still outer */") shouldBe List(nl)
      }

      "an inner comment does not close the outer one early, mid-line" in {
        lexical.scan("1 /* outer /* inner */ still outer */") shouldBe List(num("1"), nl)
      }
    }

    "interaction with line comments" - {
      "a line comment does not terminate a block comment, at start of line" in {
        // A naive "line comment wins" scan swallows the `*/` and runs to EOF.
        lexical.scan("/* ;; */\n1") shouldBe List(num("1"), nl)
      }

      "a line comment does not terminate a block comment, mid-line" in {
        lexical.scan("1 /* ;; */ 2") shouldBe List(num("1"), num("2"), nl)
      }

      "a line comment inside a block comment spans no lines, at start of line" in {
        lexical.scan("/* ;; not the end\n   still comment */\n1") shouldBe List(num("1"), nl)
      }

      "a line comment inside a block comment spans no lines, mid-line" in {
        lexical.scan("1 /* ;; not the end\n   still comment */ 2") shouldBe List(num("1"), num("2"), nl)
      }

      "a block comment start inside a line comment opens nothing, at start of line" in {
        lexical.scan(";; /* not a comment\n1") shouldBe List(num("1"), nl)
      }

      "a block comment start inside a line comment opens nothing, mid-line" in {
        lexical.scan("1 ;; /* not a comment\n2") shouldBe List(num("1"), nl, num("2"), nl)
      }

      "the configured line comment is honoured mid-line, not a hardcoded //" in {
        // `//` is two `/` delimiters for this lexer; only `;;` starts a comment.
        lexical.scan("1 // 2") shouldBe List(num("1"), kw("/"), kw("/"), num("2"), nl)
        lexical.scan("1 ;; 2") shouldBe List(num("1"), nl)
      }
    }

    "delimiters inside string literals" - {
      "an opening delimiter in a string opens nothing, at start of line" in {
        lexical.scan("\"/*\"") shouldBe List(str("/*"), nl)
      }

      "an opening delimiter in a string opens nothing, mid-line" in {
        lexical.scan("1 \"/*\" 2") shouldBe List(num("1"), str("/*"), num("2"), nl)
      }

      "a closing delimiter in a string closes nothing, at start of line" in {
        lexical.scan("\"*/\"") shouldBe List(str("*/"), nl)
      }

      "a closing delimiter in a string closes nothing, mid-line" in {
        lexical.scan("1 \"*/\" 2") shouldBe List(num("1"), str("*/"), num("2"), nl)
      }

      "a whole comment inside a string is just text, at start of line" in {
        lexical.scan("\"/* not a comment */\"") shouldBe List(str("/* not a comment */"), nl)
      }

      "a whole comment inside a string is just text, mid-line" in {
        lexical.scan("x = \"/* not a comment */\"") shouldBe List(id("x"), kw("="), str("/* not a comment */"), nl)
      }
    }

    "unterminated comments" - {
      "unterminated, at start of line" in {
        lexical.scan("/* nope") shouldBe List(err("unclosed comment"), nl)
      }

      "unterminated, mid-line" in {
        lexical.scan("1 /* nope") shouldBe List(num("1"), err("unclosed comment"), nl)
      }

      "unterminated nested, at start of line" in {
        // The inner `*/` closes only the inner comment; the outer is still open.
        // Without nesting this input is a complete comment and reports nothing.
        lexical.scan("/* outer /* inner */") shouldBe List(err("unclosed comment"), nl)
      }

      "unterminated nested, mid-line" in {
        lexical.scan("1 /* outer /* inner */") shouldBe List(num("1"), err("unclosed comment"), nl)
      }

      "reported at the opening delimiter, at start of line" in {
        scanWithPos("/* outer /* inner */") shouldBe List((err("unclosed comment"), 1, 1), (nl, 1, 21))
      }

      "reported at the opening delimiter, mid-line" in {
        scanWithPos("1 /* nope").head shouldBe ((num("1"), 1, 1))
        scanWithPos("1 /* nope")(1) shouldBe ((err("unclosed comment"), 1, 3))
      }

      "reported at the opening delimiter on a later line" in {
        scanWithPos("1\n2\n  /* nope")(4) shouldBe ((err("unclosed comment"), 3, 3))
      }

      "does not raise" in {
        noException should be thrownBy lexical.scan("/* nope")
        noException should be thrownBy lexical.scan("1 /* nope")
        noException should be thrownBy lexical.scan("/* outer /* inner */")
      }
    }

    "well-formed comments are transparent" - {
      "a comment-only input, at start of line" in {
        lexical.scan("/* just a comment */") shouldBe List(nl)
      }

      "adjacent comments, at start of line" in {
        lexical.scan("/* a */ /* b */\n1") shouldBe List(num("1"), nl)
      }

      "adjacent comments, mid-line" in {
        lexical.scan("1 /* a */ /* b */ 2") shouldBe List(num("1"), num("2"), nl)
      }

      "a comment does not disturb indentation, at start of line" in {
        lexical.scan("1\n  /* c */\n  2\n3") shouldBe List(num("1"), ind, num("2"), nl, ded, nl, num("3"), nl)
      }

      "a comment does not disturb indentation, mid-line" in {
        lexical.scan("1 /* c */\n  2 /* c */\n3") shouldBe List(num("1"), ind, num("2"), nl, ded, nl, num("3"), nl)
      }
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
