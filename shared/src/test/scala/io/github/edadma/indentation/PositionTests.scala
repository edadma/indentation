package io.github.edadma.indentation

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.util.parsing.input.CharSequenceReader

/** What a token's position says, and what asking for it costs.
 *
 * The cost is the reason these are one file rather than two. A position is built from an offset
 * into the source, and the obvious way to turn an offset into a line and a column is to walk the
 * source counting line ends — which is correct, is what `OffsetPosition` falls back to on every
 * platform but the JVM, and is quadratic once a scanner does it per token. So the answers below are
 * pinned beside the count of characters it took to reach them: a change that keeps the positions
 * right by walking the source again would pass the first half of this file and fail the second.
 */
class PositionTests extends AnyFreeSpec with Matchers {

  def lexer = new IndentationLexical(
    newlineBeforeIndent = false,
    newlineAfterDedent = true,
    startLineJoining = List("(", "["),
    endLineJoining = List(")", "]"),
    lineComment = ";;",
    blockCommentStart = "/*",
    blockCommentEnd = "*/",
  ) {
    delimiters ++= List("=", "+", "(", ")", "[", "]")
  }

  /** Every token's spelling and where the lexer says it is. */
  def positions(text: CharSequence): List[(String, Int, Int)] = {
    val lexical = lexer
    var r       = lexical.read(new CharSequenceReader(text))
    val buf     = List.newBuilder[(String, Int, Int)]

    while (!r.atEnd) {
      buf += ((r.first.chars, r.pos.line, r.pos.column))
      r = r.rest
    }

    buf.result()
  }

  /** The text of the line a token sits on, which `Position` exposes only through `longString`. */
  def lineOf(text: String, token: Int): String = {
    val lexical = lexer
    var r       = lexical.read(new CharSequenceReader(text))

    for (_ <- 0 until token) r = r.rest

    r.pos.longString.split("\n")(0)
  }

  "a token knows where it is" - {
    "on the first line" in {
      positions("a = 1") shouldBe List(("a", 1, 1), ("=", 1, 3), ("1", 1, 5), ("newline", 1, 6))
    }

    "on a later line" in {
      positions("a\nbb\nccc") shouldBe List(
        ("a", 1, 1),
        ("newline", 1, 2),
        ("bb", 2, 1),
        ("newline", 2, 3),
        ("ccc", 3, 1),
        ("newline", 3, 4),
      )
    }

    "past a blank line" in {
      positions("a\n\n\nb") shouldBe List(("a", 1, 1), ("newline", 1, 2), ("b", 4, 1), ("newline", 4, 2))
    }

    // The indent is announced where the line before it ended, which is the last position the
    // scanner had actually reached when it decided one was owed.
    "indented" in {
      positions("a\n  b") shouldBe List(
        ("a", 1, 1),
        ("indent", 1, 2),
        ("b", 2, 3),
        ("newline", 2, 4),
        ("dedent", 2, 4),
        ("newline", 2, 4),
      )
    }
  }

  // The three line endings are counted the way `OffsetPosition` counts them, so that a position
  // built from the index and one built by the library never disagree about what line something is
  // on. A lone carriage return ends a line; the one before a newline does not, since the pair is
  // one ending rather than two.
  "line endings" - {
    "a newline ends a line" in {
      positions("a\nb") shouldBe List(("a", 1, 1), ("newline", 1, 2), ("b", 2, 1), ("newline", 2, 2))
    }

    // The token points at the carriage return, which is where the ending starts — so the pair
    // reports the same column a bare newline would.
    "a carriage return and a newline are one ending" in {
      positions("a\r\nb") shouldBe List(("a", 1, 1), ("newline", 1, 2), ("b", 2, 1), ("newline", 2, 2))
    }

    "a lone carriage return ends a line" in {
      positions("a\rb") shouldBe List(("a", 1, 1), ("b", 2, 1), ("newline", 2, 2))
    }
  }

  "a position carries the text of its line" - {
    "without the newline that ended it" in {
      lineOf("first\nsecond\nthird\n", 2) shouldBe "second"
    }

    "without a carriage return either" in {
      lineOf("first\r\nsecond\r\nthird\r\n", 2) shouldBe "second"
    }

    "on the last line, which nothing ended" in {
      lineOf("first\nlast", 2) shouldBe "last"
    }
  }

  /** A source that reports how many characters were read out of it. */
  class Counted(text: String) extends CharSequence {
    var reads = 0

    def length: Int                                    = text.length
    def charAt(i: Int): Char                           = { reads += 1; text.charAt(i) }
    def subSequence(start: Int, end: Int): CharSequence = text.subSequence(start, end)
    override def toString: String                      = text
  }

  /** Characters read while lexing `lines` lines and asking every token where it is. */
  def readsToScan(lines: Int, askPos: Boolean = true): (Int, Int) = {
    val text = (1 to lines).map(i => s"a$i = $i + $i").mkString("\n") + "\n"
    val src  = new Counted(text)
    var r    = lexer.read(new CharSequenceReader(src))

    while (!r.atEnd) {
      if (askPos) {
        r.pos.line
        r.pos.column
      }
      r = r.rest
    }

    (src.reads, text.length)
  }

  "asking every token where it is costs a pass over the source, not a pass per token" - {

    // Doubling the input doubles the work if the source is indexed once, and quadruples it if each
    // position indexes it again. The bound is between the two so that it is the *shape* of the
    // growth being asserted rather than any particular machine's constant.
    "twice the source is not four times the work" in {
      val (small, _) = readsToScan(200)
      val (large, _) = readsToScan(400)

      large.toDouble / small should be < 3.0
    }

    // The index is built whether or not anything asks, so asking is free. Stating it as an
    // equality rather than a bound is what makes it catch a position that walks the source again:
    // such a position costs nothing when nobody asks and everything when something does.
    "asking adds nothing to what the scan already read" in {
      val (asking, _)    = readsToScan(400)
      val (notAsking, _) = readsToScan(400, askPos = false)

      asking shouldBe notAsking
    }

    // A single pass to build the index, plus whatever lexing itself re-reads while backtracking.
    // Before the source was indexed once this was a full pass per token, which is hundreds of
    // times over rather than tens.
    "the whole source is read a bounded number of times" in {
      val (reads, length) = readsToScan(400)

      reads.toDouble / length should be < 30.0
    }
  }
}
