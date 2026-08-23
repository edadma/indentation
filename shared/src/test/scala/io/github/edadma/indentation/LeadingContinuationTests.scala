package io.github.edadma.indentation

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.util.parsing.input.Reader

/** A line that continues the one above it because of how it *begins*.
  *
  * `isLineContinuationToken` decides from the token a line ends with, which is enough for a trailing
  * `+` and not for the style every fluent API is actually written in — the dot at the start of the
  * next line. That needs the opposite mechanism, a look at what is coming rather than at what has
  * been, and these are its tests.
  *
  * **Tokens are compared by their text rather than by identity**, because a lexer's token classes
  * are inner to the instance that made them: two lexers configured identically produce tokens that
  * print the same and are equal to nothing but their own. Comparing `chars` is what lets one
  * expectation be read against two lexers, which half of these tests need.
  */
class LeadingContinuationTests extends AnyFreeSpec with Matchers {

  private def lexer(f: Reader[Char] => Boolean) =
    new IndentationLexical(
      newlineBeforeIndent = true,
      newlineAfterDedent = true,
      startLineJoining = List("("),
      endLineJoining = List(")"),
      lineComment = "//",
      blockCommentStart = "/*",
      blockCommentEnd = "*/",
    ) {
      delimiters ++= List(".", "+", "(", ")", "=")

      override protected def isLineContinuationStart(r: Reader[Char]): Boolean = f(r)
    }

  /** A lexer that joins a line beginning `.name`, and nothing else.
    *
    * The predicate is deliberately narrower than "starts with a dot": `..` is a range in most
    * languages that have one, and a rule that swallowed it would join two statements. Requiring a
    * letter after the dot is the whole guard.
    */
  private val chaining = lexer(r => !r.atEnd && r.first == '.' && !r.rest.atEnd && r.rest.first.isLetter)

  /** The same lexer with the hook left alone, so that every assertion below can be read against what
    * the library did before it existed.
    */
  private val plain = lexer(_ => false)

  private def scan(l: IndentationLexical, s: String): List[String] = l.scan(s).map(_.chars)

  private def chained(s: String): List[String] = scan(chaining, s)

  "a line beginning with a dot" - {
    "joins the line above it" in {
      chained("a\n    .b") shouldBe List("a", ".", "b", "newline")
    }

    "joins however many lines there are" in {
      chained("a\n    .b\n    .c\n    .d") shouldBe
        List("a", ".", "b", ".", "c", ".", "d", "newline")
    }

    "does not indent, so the statement after it is a sibling and not a child" in {
      chained("a\n    .b\nc") shouldBe List("a", ".", "b", "newline", "c", "newline")
    }

    "carries no meaning in its own margin, so it may be laid out however reads best" in {
      val flush    = chained("x = a\n.b")
      val indented = chained("x = a\n        .b")

      flush shouldBe indented
      flush shouldBe List("x", "=", "a", ".", "b", "newline")
    }
  }

  "what it must not disturb" - {
    "a line beginning with anything else is untouched" in {
      chained("a\n    b") shouldBe List("a", "newline", "indent", "b", "newline", "dedent", "newline")
    }

    "a dot followed by a dot is a range and not a chain" in {
      chained("a\n    ..b") shouldBe
        List("a", "newline", "indent", ".", ".", "b", "newline", "dedent", "newline")
    }

    "a dot in the middle of a line was never this rule's business" in {
      chained("a.b") shouldBe List("a", ".", "b", "newline")
    }

    "the dedent that ends a block still arrives when the chain is inside one" in {
      chained("a\n    b\n        .c\nd") shouldBe
        List("a", "newline", "indent", "b", ".", "c", "newline", "dedent", "newline", "d", "newline")
    }

    "a lexer that does not override the hook behaves exactly as it did" in {
      scan(plain, "a\n    .b") shouldBe
        List("a", "newline", "indent", ".", "b", "newline", "dedent", "newline")
    }
  }

  "the reader it is handed has already been stepped past" - {
    "a comment line sitting in the middle of a chain" in {
      chained("a\n    // why\n    .b") shouldBe List("a", ".", "b", "newline")
    }

    "a blank line sitting in the middle of a chain" in {
      chained("a\n\n    .b") shouldBe List("a", ".", "b", "newline")
    }

    "and the leading whitespace of the line itself" in {
      chained("a\n\t.b") shouldBe List("a", ".", "b", "newline")
    }
  }

  "it composes with the joining the library already did" - {
    "a chain inside brackets, where newlines were suppressed anyway" in {
      chained("f(a\n    .b)") shouldBe List("f", "(", "a", ".", "b", ")", "newline")
    }

    "a chain after a line that ended in an open bracket" in {
      chained("f(\n  a\n   .b\n)") shouldBe List("f", "(", "a", ".", "b", ")", "newline")
    }
  }

  "the previous token, for a predicate that needs both ends" - {
    // The case this exists for: a language where a block body can itself begin with a dot. Joining
    // after a keyword that opens one would read the body's first line as a continuation of the
    // header, so a predicate consulting `previousToken` declines exactly there and nowhere else.
    "a predicate may decline after a token that cannot end an expression" in {
      val fussy = new IndentationLexical(
        newlineBeforeIndent = true,
        newlineAfterDedent = true,
        startLineJoining = List("("),
        endLineJoining = List(")"),
        lineComment = "//",
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
      ) {
        delimiters ++= List(".", "+", "(", ")", "=")
        // A word is made a keyword by `reserved`, not by `delimiters` — `delimiters` is for symbols.
        reserved += "match"

        override protected def isLineContinuationStart(r: Reader[Char]): Boolean =
          !r.atEnd && r.first == '.' && !r.rest.atEnd && r.rest.first.isLetter &&
            previousToken != Keyword("match")
      }

      // After an ordinary name the chain joins, as it always did.
      scan(fussy, "a\n    .b") shouldBe List("a", ".", "b", "newline")

      // After the keyword it does not, so the block below it is a block.
      scan(fussy, "a match\n    .b") shouldBe
        List("a", "match", "newline", "indent", ".", "b", "newline", "dedent", "newline")
    }

    "and it is null before anything has been emitted" in {
      val first = new IndentationLexical(
        newlineBeforeIndent = true,
        newlineAfterDedent = true,
        startLineJoining = List("("),
        endLineJoining = List(")"),
        lineComment = "//",
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
      ) {
        delimiters ++= List(".", "(", ")")

        // Reading the token without guarding against null is the mistake this asserts is possible
        // to make and cheap to avoid.
        override protected def isLineContinuationStart(r: Reader[Char]): Boolean =
          previousToken != null && !r.atEnd && r.first == '.'
      }

      scan(first, ".a\n.b") shouldBe List(".", "a", ".", "b", "newline")
    }
  }

  "the hazard it shares with every joining rule" - {
    // A continuation line's indentation is discarded, which is the whole point of continuing. So a
    // predicate that accepted a leading token which could also *begin* a statement would pull a line
    // written at the outer margin into the block above it: below, `+ c` sits at column 0 and is
    // still inside the body of `a`, because the newline that would have closed the block was
    // suppressed. The block ends one line later than it looks like it does.
    //
    // This is why a real language's predicate asks for a name after the dot rather than merely a
    // dot, and why the hook's documentation says what it says.
    "a predicate that accepts what could start a statement pulls a line into the block above" in {
      val reckless = lexer(r => !r.atEnd && r.first == '+')

      scan(reckless, "a\n    b\n+ c\nd") shouldBe
        List("a", "newline", "indent", "b", "+", "c", "newline", "dedent", "newline", "d", "newline")
    }
  }
}
