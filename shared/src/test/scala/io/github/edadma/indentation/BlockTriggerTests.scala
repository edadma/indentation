package io.github.edadma.indentation

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** A token that opens an indented block opens one **inside brackets too**.
 *
 * The bracket rule says a newline between `(` and `)` is not a newline, which is what lets an
 * argument list be laid out however reads best. A block opened inside such a list is the one place
 * that rule is wrong: the body's margin is the only thing saying where it ends, so the Newline,
 * Indent and Dedent tokens its parser needs have to be emitted after all.
 *
 * `blockTriggerToken` says which one token does that; `isBlockTrigger` says which ones do, and is
 * what a language with more than one overrides. Neither had a test before this suite — the feature
 * was reachable and unexercised.
 */
class BlockTriggerTests extends AnyFreeSpec with Matchers {

  private def lexer(trigger: Option[String], predicate: Set[String] = Set.empty) =
    new IndentationLexical(
      newlineBeforeIndent = true,
      newlineAfterDedent = true,
      startLineJoining = List("(", "[", "{"),
      endLineJoining = List(")", "]", "}"),
      lineComment = "//",
      blockCommentStart = "/*",
      blockCommentEnd = "*/",
      blockTriggerToken = trigger,
    ) {
      reserved ++= List("match", "else")
      delimiters ++= List("(", ")", "[", "]", "{", "}", "->", ",", "+")

      override protected def isBlockTrigger(tok: Token): Boolean =
        if predicate.isEmpty then super.isBlockTrigger(tok)
        else tok match
          case Keyword(chars) => predicate(chars)
          case _              => false
    }

  private def words(ts: List[Any]): List[String] = ts.map(_.toString)

  // A delimiter renders itself in single quotes, so the expectations below name it that way rather
  // than repeating the escaping at each assertion.
  private val closeParen = "')'"
  private val comma      = "','"

  "with no trigger, a bracket pair suppresses every newline inside it" in {
    val ts = words(lexer(None).scan("f(x match\n    1 -> a\n    else b)"))

    ts should not contain "Indent"
    ts should not contain "Dedent"
  }

  "the single-token form opens a block on that token and closes it at the bracket" in {
    val ts = words(lexer(Some("match")).scan("f(x match\n    1 -> a\n    else b)"))

    ts should contain("Indent")
    ts should contain("Dedent")
    // The block ends where the argument list does: the `)` drains the body's dedent, and the call
    // goes on to be closed by the very same token.
    ts.takeRight(3) shouldBe List("Dedent", closeParen, "Newline")
  }

  "and only on that token — a second block form is still joined away" in {
    val ts = words(lexer(Some("match")).scan("f((x) ->\n    a\n    b)"))

    ts should not contain "Indent"
  }

  "the predicate takes as many tokens as the language has" - {
    val both = Set("match", "->")

    "the first of them" in {
      val ts = words(lexer(None, both).scan("f(x match\n    1 -> a\n    else b)"))

      ts should contain("Indent")
      ts should contain("Dedent")
    }

    "the second of them, which the single-token form could not have reached at the same time" in {
      val ts = words(lexer(None, both).scan("f((x) ->\n    a\n    b)"))

      ts should contain("Indent")
      ts.takeRight(3) shouldBe List("Dedent", closeParen, "Newline")
    }

    "a bracket pair with no trigger in it is joined exactly as before" in {
      val ts = words(lexer(None, both).scan("f(a,\n    b,\n    c)"))

      ts should not contain "Indent"
      ts should not contain "Dedent"
      // The last token is the newline every input ends with; what the bracket rule suppresses is
      // the two inside it.
      ts.init should not contain "Newline"
    }

    // The frame is restored when the body dedents, so what follows the block is an ordinary
    // argument list again rather than a region where margins still mean something.
    "an argument after the block is read at the outer level" in {
      val ts = words(lexer(None, both).scan("f(x match\n    1 -> a\n    else b, c)"))

      ts should contain("Dedent")
      ts.takeRight(4) shouldBe List(comma, "identifier c", closeParen, "Newline")
    }
  }

  "a trigger outside any bracket is the ordinary block it always was" in {
    val ts = words(lexer(None, Set("match", "->")).scan("x match\n    1 -> a\n"))

    ts should contain("Indent")
    ts should contain("Dedent")
  }
}
