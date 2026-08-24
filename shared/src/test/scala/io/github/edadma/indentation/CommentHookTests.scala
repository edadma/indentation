package io.github.edadma.indentation

import scala.collection.mutable
import scala.util.parsing.input.Reader

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** A comment is trivia, and `comment` is how a language gets one anyway.
 *
 * The hook exists for documentation comments: a generator, or an editor's hover text, needs the
 * prose above a declaration, and the token stream is deliberately not where that belongs. So the
 * lexer reports each comment as it consumes it and emits nothing — a consumer that does not
 * override the hook lexes exactly as it did before there was one.
 *
 * Both comment paths are covered here, because there are two and they are easy to conflate: the
 * start-of-line scan in `skipLinePrefix`, which steps over a comment *before* indentation is
 * measured, and the mid-line scan in `whitespace`. Neither had a test of any kind before this
 * suite — the comment machinery was reachable, load-bearing and unexercised, exactly as
 * `isBlockTrigger` was before `BlockTriggerTests`.
 */
class CommentHookTests extends AnyFreeSpec with Matchers {

  /** A lexer that records every comment it is told about, as text, keyed by offset.
   *
   * Keyed rather than appended because the hook's contract says a comment may be reported more
   * than once — see the lookahead test at the end, which is the case that makes it so.
   */
  private class Recording extends IndentationLexical(
        newlineBeforeIndent = true,
        newlineAfterDedent = true,
        startLineJoining = List("(", "[", "{"),
        endLineJoining = List(")", "]", "}"),
        lineComment = "//",
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
      ) {
    reserved ++= List("if", "then")
    delimiters ++= List("(", ")", "[", "]", "{", "}", "+", "=", ",")

    val seen = new mutable.LinkedHashMap[Int, String]

    /** How many times the hook fired, as against how many distinct comments it reported. The
     * difference is the lookahead, and a test asserts on it rather than leaving it to be
     * discovered.
     */
    var calls = 0

    override protected def comment(from: Reader[Char], to: Reader[Char]): Unit = {
      calls += 1
      seen(from.offset) = from.source.subSequence(from.offset, to.offset).toString
    }

    /** The comments in source order, which is what a documentation generator reads. */
    def texts: List[String] = seen.toList.sortBy(_._1).map(_._2)
  }

  private def record(s: String): Recording = {
    val lex = new Recording
    lex.scan(s)
    lex
  }

  "the default hook does nothing, so a comment is trivia exactly as before" in {
    val plain = new IndentationLexical(
      newlineBeforeIndent = true,
      newlineAfterDedent = true,
      startLineJoining = List("("),
      endLineJoining = List(")"),
      lineComment = "//",
      blockCommentStart = "/*",
      blockCommentEnd = "*/",
    ) {
      delimiters ++= List("(", ")", "=")
    }

    val withComments = plain.scan("a = 1 // trailing\n/* leading */ b = 2\n").map(_.toString)
    val without      = plain.scan("a = 1\nb = 2\n").map(_.toString)

    withComments shouldBe without
  }

  "a comment at the start of a line is reported" - {
    "the line-comment form, with its text and nothing after the newline" in {
      record("// a leading note\na = 1\n").texts shouldBe List("// a leading note")
    }

    "the block-comment form" in {
      record("/* a leading note */\na = 1\n").texts shouldBe List("/* a leading note */")
    }

    "several on their own lines, in source order" in {
      record("// one\n// two\na = 1\n// three\nb = 2\n").texts shouldBe
        List("// one", "// two", "// three")
    }

    "and one that runs to end of input with no trailing newline" in {
      record("a = 1\n// last word").texts shouldBe List("// last word")
    }
  }

  "a comment in the middle of a line is reported" - {
    "trailing a statement" in {
      record("a = 1 // why\n").texts shouldBe List("// why")
    }

    "between two tokens" in {
      record("a = /* why */ 1\n").texts shouldBe List("/* why */")
    }

    "twice on one line, in order" in {
      record("a = /* one */ 1 // two\n").texts shouldBe List("/* one */", "// two")
    }
  }

  "the text handed over is the whole comment, delimiters included" - {
    "so a documentation form is told from an ordinary one by its own first characters" in {
      val lex = record("/** the doc */\n/* not the doc */\na = 1\n")

      lex.texts shouldBe List("/** the doc */", "/* not the doc */")
      lex.texts.filter(_.startsWith("/**")) shouldBe List("/** the doc */")
    }

    "and a block comment spanning lines arrives whole, newlines and all" in {
      record("/* one\n   two\n   three */\na = 1\n").texts shouldBe
        List("/* one\n   two\n   three */")
    }
  }

  "a nested block comment is one comment, reported once, to its outermost end" in {
    val lex = record("/* outer /* inner */ still outer */\na = 1\n")

    lex.texts shouldBe List("/* outer /* inner */ still outer */")
  }

  "an unclosed block comment is not reported, because it never ends" in {
    // `scanBlockComment` answers `None`, and there is no `to` reader to hand over. The lexer's own
    // error token is what says so; the hook has nothing to say about a comment with no extent.
    record("a = 1\n/* never closed\n").texts shouldBe empty
  }

  "the offset is the comment's own, so a consumer can find what follows it" in {
    val lex = record("a = 1\n// about b\nb = 2\n")

    lex.seen.keys.toList shouldBe List(6)
    lex.seen(6) shouldBe "// about b"
  }

  "a comment inside a bracket pair is reported, though the newline around it is not" in {
    val src = "f(\n  1, // first\n  2,\n)\n"

    record(src).texts shouldBe List("// first")
    record(src).scan(src).map(_.toString) should not contain "Indent"
  }

  "THE SAME COMMENT IS REPORTED TWICE, which is why the contract says to key by offset" in {
    // The lexer looks ahead one line to decide whether it continues the one above:
    //
    //   !in.rest.atEnd && isLineContinuationStart(skipLinePrefix(skipBlankLines(in.rest)))
    //
    // The argument is evaluated whatever the predicate answers — and it defaults to `false`, so
    // this fires for every language, not only one using leading continuations. The line is then
    // scanned again for real. A comment in that prefix is therefore seen by both passes.
    //
    // Keying by offset makes it idempotent; appending to a list would not, and this is the input
    // that would otherwise have found that out in somebody's generated documentation.
    val lex = record("a = 1\n// about the next line\nb = 2\n")

    lex.texts shouldBe List("// about the next line")
    lex.calls should be > lex.texts.length
  }
}
