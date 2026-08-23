package io.github.edadma.indentation

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.parsing.combinator.lexical.StdLexical
import scala.util.parsing.combinator.token.Tokens
import scala.util.parsing.input.{CharSequenceReader, Position, Reader}
import scala.collection.mutable.{ListBuffer, Stack}
import scala.compiletime.uninitialized

/** Indentation-aware lexer with optional line-joining inside paren / brace / bracket
 *  pairs.
 *
 *  `blockTriggerToken` opt-in: when set (e.g. `Some("->")`), the lexer recognizes
 *  it as the start of an indented block body and **suspends line-joining** for the
 *  body's extent — even when the trigger appears inside an outer paren context.
 *  Concretely: if the trigger token is the most recently emitted real token and
 *  the very next character begins a newline while `lineJoining > 0`, the lexer
 *  records the current `lineJoining` count + indent level on a stack, sets
 *  `lineJoining = 0`, and proceeds with normal Newline/Indent/Dedent emission for
 *  the body. When dedent brings the indent stack back at or below the recorded
 *  level, the frame is popped and `lineJoining` is restored — so the rest of the
 *  enclosing call's argument list parses normally.
 *
 *  Without this feature, multi-statement closure bodies inside parens (e.g.
 *  `f((x: int) -> \n var acc = 0 \n acc + 1)`) cannot parse, because the lexer
 *  suppresses the Newline/Indent/Dedent tokens that the block-statement parser
 *  needs.
 *
 *  Comments use the configured `lineComment`, `blockCommentStart` and
 *  `blockCommentEnd` in every position — at the start of a line and in the middle
 *  of one alike. Block comments nest, so a region that already contains a comment
 *  can be commented out. A block comment that is never closed produces an error
 *  token at its opening delimiter rather than raising. */
class IndentationLexical(
    newlineBeforeIndent: Boolean,
    newlineAfterDedent: Boolean,
    startLineJoining: List[String],
    endLineJoining: List[String],
    lineComment: String,
    blockCommentStart: String,
    blockCommentEnd: String,
    blockTriggerToken: Option[String] = None,
) extends StdLexical {

  private val level                  = new mutable.Stack[Int]
  private var state: Int             = uninitialized
  private var current: Int           = uninitialized
  private var finalnl                = false
  private var dedentnl               = false
  private var lineJoining            = 0
  private val startLineJoiningTokens = startLineJoining map (Keyword(_))
  private val endLineJoiningTokens   = endLineJoining map (Keyword(_))
  private val triggerKeyword: Option[Keyword] = blockTriggerToken.map(Keyword(_))
  // Frames pushed when `triggerKeyword` is followed by an indented block while in
  // line-joining mode. Each frame is (savedLineJoining, levelTopBeforeBody).
  private val joiningFrames          = new mutable.Stack[(Int, Int)]
  // Most recently emitted non-structural token (used to detect "the trigger token
  // immediately precedes a newline").
  private var lastEmittedToken: Token = null
  // Single-char close-line-joining markers — used to terminate a triggered block
  // body when the matching close-delim appears on the body's last line (rather
  // than on a fresh dedented line).
  private val endLineJoiningChars: Set[Char] =
    endLineJoining.collect { case s if s.length == 1 => s.charAt(0) }.toSet
  // True while we're emitting a drain-triggered Dedent sequence — used to suppress
  // the trailing post-Dedent Newline that newlineAfterDedent normally emits, since
  // there is no real Newline character at the outer indent (the next character is
  // the close-delim, e.g. `)`).
  private var drainDedent: Boolean = false
  // Line starts of the character sequence being scanned, so that a token's line and
  // column cost a binary search instead of a walk. Null when the reader `read` was
  // given is not backed by one, which is the only case that falls back to asking the
  // character reader's own position.
  private var lineIndex: LineIndex = null

  case object Newline extends Token { val chars = "newline" }
  case object Indent  extends Token { val chars = "indent"  }
  case object Dedent  extends Token { val chars = "dedent"  }

  /** Override to mark a token as one that, when it appears immediately before a
   *  newline outside any paren/bracket/brace pair, should suppress the implicit
   *  Newline (and any following indent change) — i.e. treat the next line as a
   *  continuation of the current expression. Mirrors what `(` `[` `{` already
   *  do via `lineJoining`, but driven by the *trailing* token instead of the
   *  enclosing-pair counter.
   *
   *  The default returns false (no trailing-token continuation; only paren-pair
   *  joining is in effect). Subclasses should return true for binary operators
   *  whose presence at end-of-line unambiguously signals "RHS coming" — and
   *  must NOT include tokens that legitimately end a statement (postfix `++` /
   *  `--`) or that drive their own indented-block parser construct (`=`, `->`,
   *  `=>`). */
  protected def isLineContinuationToken(tok: Token): Boolean = false

  /** Override to mark a *line* as a continuation of the one before it, decided by
   *  looking at the text it begins with rather than at the token it follows.
   *
   *  This is the mirror image of `isLineContinuationToken` and exists because the
   *  two continuation styles need opposite mechanisms. A trailing operator says
   *  "more is coming" and is known when the newline is reached; a leading one —
   *  the `.` of a call chain, as Scala, Kotlin and Swift all write it — says
   *  "this belongs to what came before" and is knowable only by looking ahead:
   *
   *  {{{
   *  val v = text(label)
   *      .padding(8)
   *      .background(blue)
   *  }}}
   *
   *  The reader passed in is positioned at the first character of code on the next
   *  line: leading whitespace, blank lines and comments have already been stepped
   *  over, so an implementation reads the line's opening characters and nothing
   *  else. Returning true suppresses the Newline and any Indent or Dedent that the
   *  line's margin would otherwise have produced — so, exactly as with a trailing
   *  operator, a continuation line's indentation carries no meaning and it may be
   *  laid out however reads best.
   *
   *  The default returns false, so a lexer that does not override this behaves as
   *  it always did.
   *
   *  An implementation must be sure the text it accepts can never begin a
   *  statement of its own, because a line this claims is joined to the one above
   *  and its own indentation is discarded — a leading token that could also open a
   *  statement would silently swallow the dedent that ended a block. A `.`
   *  followed by a name is the safe case and is why the rule is worth having; a
   *  bare `-` is not, since a statement may begin with a negation. */
  protected def isLineContinuationStart(r: Reader[Char]): Boolean = false

  def num(s: String) = NumericLit(s)

  def scan(s: String): List[Token] = {
    val buf = new ListBuffer[Token]
    var t   = read(new CharSequenceReader(s))

    while (!t.atEnd) {
      buf append t.first
      t = t.rest
    }

    buf.toList
  }

  private def matches(r: Reader[Char], s: String): Boolean = {
    var input   = r
    var i       = 0
    var matched = true

    while (i < s.length && matched) {
      if (!input.atEnd && s.charAt(i) == input.first) {
        input = input.rest
        i += 1
      } else {
        matched = false
      }
    }

    matched
  }

  private def skip(r: Reader[Char], pred: Reader[Char] => Boolean): Reader[Char] =
    if (pred(r))
      r
    else
      skip(r.rest, pred)

  private def skipSpace(r: Reader[Char]) = skip(r, a => a.atEnd || a.first != '\t' && a.first != ' ' && a.first != '\r')

  /** Whether two readers have reached the same place.
   *
   *  By offset, which is the cheap question and the one being asked. Comparing the
   *  *positions* asks the same thing through `Position.equals`, which is defined as
   *  equal lines and equal columns — and computing a line from an offset means
   *  indexing the source, which `LineIndex` describes the cost of. Two readers over
   *  one source are at the same line and column exactly when they are at the same
   *  offset, so nothing is given up. */
  private def samePlace(a: Reader[Char], b: Reader[Char]): Boolean =
    try a.offset == b.offset
    catch { case _: NoSuchMethodError => a.pos == b.pos }

  private def skipToEOL(r: Reader[Char]) = skip(r, a => a.atEnd || a.first == '\n')

  private def skipToEnd(r: Reader[Char]) = skip(r, _.atEnd)

  /** Message carried by the error token reported for a block comment that is never
   *  closed. The scanner recognizes it so that the remainder of the input — which
   *  is all comment text — is consumed rather than lexed into spurious tokens
   *  trailing the real diagnostic. */
  private val UnclosedComment = "unclosed comment"

  private def atLineComment(r: Reader[Char])  = lineComment.nonEmpty && matches(r, lineComment)
  private def atBlockComment(r: Reader[Char]) = blockCommentStart.nonEmpty && matches(r, blockCommentStart)

  /** Scan a block comment. `r` must be positioned at `blockCommentStart`; the
   *  result is the reader just past the matching `blockCommentEnd`, or `None` if
   *  the comment is never closed.
   *
   *  Block comments nest, as they do in Scala, Rust, Swift and D — that is what
   *  makes it possible to comment out a region that already contains a comment.
   *  Nothing inside a block comment is otherwise interpreted: a line comment does
   *  not terminate it, and a quote does not begin a string literal.
   *
   *  This is the single implementation behind both comment paths — the
   *  start-of-line scan in `skipBlankLines` and the mid-line scan in `whitespace`
   *  — so the two cannot drift apart. */
  private def scanBlockComment(r: Reader[Char]): Option[Reader[Char]] = {
    @tailrec
    def loop(in: Reader[Char], depth: Int): Option[Reader[Char]] =
      if (depth == 0) Some(in)
      else if (in.atEnd) None
      else if (atBlockComment(in)) loop(in.drop(blockCommentStart.length), depth + 1)
      else if (matches(in, blockCommentEnd)) loop(in.drop(blockCommentEnd.length), depth - 1)
      else loop(in.rest, depth)

    loop(r.drop(blockCommentStart.length), 1)
  }

  /** Skip a line's leading whitespace and any comments that follow it, stopping at
   *  the first thing that is none of those. What it stops on says what the line is:
   *  a newline or end of input means the line carries no code; the opening
   *  delimiter of a block comment means that comment is never closed (a closed one
   *  would have been consumed); anything else is code. */
  @tailrec
  private def skipLinePrefix(r: Reader[Char]): Reader[Char] = {
    val r1 = skipSpace(r)

    if (r1.atEnd || r1.first == '\n') r1
    else if (atLineComment(r1)) skipToEOL(r1.drop(lineComment.length))
    else if (atBlockComment(r1))
      scanBlockComment(r1) match {
        case Some(r2) => skipLinePrefix(r2)
        case None     => r1
      }
    else r1
  }

  /** Advance past every line that carries no code, leaving the reader at the start
   *  of one that does — *before* its leading whitespace, so that the caller
   *  measures the indentation from the line's own margin.
   *
   *  Returning a position part-way into the line would make the gap after a
   *  comment that precedes the code into the line's indentation, which is both
   *  wrong on its own and, being a level like any other, wrong for every
   *  comparison against the lines that follow. The comment itself does not need to
   *  be skipped here — `whitespace` consumes it before the next token. */
  @tailrec
  private def skipBlankLines(r: Reader[Char]): Reader[Char] = {
    val end = skipLinePrefix(r)

    if (end.atEnd)
      end
    else if (end.first == '\n')
      skipBlankLines(end.rest)
    // An unclosed block comment runs to end of input, so this line has no code
    // either. Stop at the opening delimiter: no indentation is measured for a line
    // that has nothing to indent, and `whitespace` reports the comment from there,
    // the way every other lexical failure is reported.
    else if (atBlockComment(end))
      end
    else
      r
  }

  def read(in: Reader[Char]): Reader[Token] = {
    level.clear()
    level.push(0)
    state = BLOCK_STATE
    finalnl = false
    dedentnl = false
    lineJoining = 0
    joiningFrames.clear()
    lastEmittedToken = null
    lineIndex =
      try new LineIndex(in.source)
      catch { case _: NoSuchMethodError => null }
    new IndentationScanner(skipBlankLines(in))
  }

  override def whitespaceChar = elem("space char", c => c == ' ' || c == '\t' || c == '\r')

  private lazy val lineCommentParser: Parser[Any] = Parser { in =>
    if (atLineComment(in)) Success((), skipToEOL(in.drop(lineComment.length)))
    else Failure("not a line comment", in)
  }

  private lazy val blockCommentParser: Parser[Any] = Parser { in =>
    if (atBlockComment(in))
      scanBlockComment(in) match {
        case Some(after) => Success((), after)
        // `Error` (unlike `Failure`) propagates out of the enclosing `rep`, and
        // carries `in` — the opening delimiter — as its position.
        case None => Error(UnclosedComment, in)
      }
    else Failure("not a block comment", in)
  }

  /** Whitespace and comments between tokens.
   *
   *  `StdLexical`'s version hardcodes `/*`, `*/` and `//`, ignoring the configured
   *  delimiters, and does not nest. This one delegates to the same helpers as the
   *  start-of-line path, so a comment lexes identically wherever it appears.
   *
   *  Newlines are deliberately not whitespace here — they are what `whitespaceChar`
   *  excludes and what the indentation machinery runs on. A line comment therefore
   *  stops at the newline it precedes, while a block comment may span newlines and
   *  join the lines it spans. */
  private lazy val whitespaceParser: Parser[Any] =
    rep[Any](whitespaceChar | lineCommentParser | blockCommentParser)

  override def whitespace: Parser[Any] = whitespaceParser

  private val BLOCK_STATE   = 1
  private val INDENT_STATE  = 2
  private val DEDENT_STATE  = 3
  private val NEWLINE_STATE = 4

  // After level.pop(), restore line-joining if we've dedented back to the level
  // recorded on the most recent block-trigger frame.
  private def maybeRestoreJoiningFrame(): Unit =
    while (joiningFrames.nonEmpty && level.nonEmpty && level.top <= joiningFrames.top._2) {
      val (savedJoining, _) = joiningFrames.pop()
      lineJoining = savedJoining
    }

  class IndentationScanner(in: Reader[Char]) extends Reader[Token] {

    private def skipWhiteSpace(r: Reader[Char]): ParseResult[Any] = {
      whitespace(r) match {
        case res @ Success(_, in1) =>
          if (!in1.atEnd && in1.first == '\n')
            skipWhiteSpace(in1.rest)
          else
            res
        case res => res
      }
    }

    private val (tok, rest1, rest2) = {
      whitespace(in) match {
        case Success(_, in0) =>
          IndentationParser(in0) match {
            case Success(tok, in2) =>
              (tok, in, in2)
            // `Failure` means "no indentation token here", which is ordinary — fall
            // through and lex a real token. `Error` means the indentation itself is
            // malformed, and must be reported: matching it alongside `Failure` here
            // is what made the diagnostic below unreachable.
            case e: Error => failed(e)
            case Failure(_, _) =>
              skipWhiteSpace(in) match {
                case Success(_, in1) =>
                  token(in1) match {
                    case Success(tok, in2) =>
                      if (startLineJoiningTokens contains tok)
                        lineJoining += 1
                      else if (endLineJoiningTokens contains tok)
                        lineJoining -= 1

                      lastEmittedToken = tok
                      (tok, in1, in2)
                    case ns: NoSuccess => failed(ns)
                  }
                case ns: NoSuccess => failed(ns)
              }
          }
        case ns: NoSuccess => failed(ns)
      }
    }

    /** Turn a lexical failure into an error token positioned where the failure was
     *  reported. An unterminated block comment is reported at its opening
     *  delimiter, and everything after it is comment text — so it is consumed
     *  whole, rather than resuming one character in and trailing the real
     *  diagnostic with junk tokens. */
    private def failed(ns: NoSuccess): (Token, Reader[Char], Reader[Char]) =
      (errorToken(ns.msg), ns.next, if (ns.msg == UnclosedComment) skipToEnd(ns.next) else skip(ns.next))

    private def skip(in: Reader[Char]) = if (in.atEnd) in else in.rest

    /** The underlying character sequence being scanned. */
    override def source: java.lang.CharSequence = in.source

    /** Offset into `source` of the current position. */
    override def offset: Int = in.offset

    private def atend = in.atEnd || (skipWhiteSpace(in) match {
      case Success(_, in1) => in1.atEnd
      case _               => false
    })

    val atEnd = atend && finalnl && !dedentnl && level.size == 1

    lazy val first = gettoken

    private def gettoken = {
      val res =
        if (atend)
          if (!finalnl || dedentnl)
            Newline
          else if (level.size > 1)
            Dedent
          else
            sys.error("no more tokens")
        else
          tok

      res
    }

    lazy val rest =
      if (atend)
        if (!finalnl) {
          finalnl = true
          new IndentationScanner(rest1)
        } else if (dedentnl) {
          dedentnl = false
          new IndentationScanner(rest1)
        } else if (level.size > 1) {
          if (newlineAfterDedent)
            dedentnl = true

          level.pop()
          maybeRestoreJoiningFrame()
          new IndentationScanner(rest1)
        } else
          sys.error("no more tokens")
      else
        new IndentationScanner(rest2)

    lazy val pos: PositionWrapper = positionOf(rest1)
  }

  /** Where `r` sits, as a position that already knows its line and column.
   *
   *  The index answers in a binary search; asking the character reader for its own
   *  position does not, for the reason `LineIndex` describes. The fallback is for a
   *  reader that is not backed by a character sequence at all, where there is no
   *  index to have built and the library's own position is the only answer. */
  private def positionOf(r: Reader[Char]): PositionWrapper = {
    val idx = lineIndex

    if (idx != null && (r.source eq idx.source)) {
      val line = idx.lineOf(r.offset)

      new PositionWrapper(line, idx.columnOf(r.offset, line), () => idx.contentsOf(line))
    } else new PositionWrapper(r.pos)
  }

  /** The start offset of every line of a character sequence, so that an offset can be
   *  turned into a line and a column without walking the sequence.
   *
   *  `OffsetPosition` builds exactly this index and caches it against the source — but
   *  only on the JVM. The Scala.js and Scala Native builds of
   *  `scala-parser-combinators` substitute a map that discards what it is given ("the
   *  /dev/null of Maps", in its own words), so on those platforms the index is rebuilt
   *  by every position that is asked for its line or column. A scanner asks once per
   *  token, which makes lexing a file quadratic in the length of the file: a compiler
   *  front end spent four and a half seconds on a two-line program, nearly all of it
   *  re-indexing the same standard library. Indexing the source once, here, is what
   *  keeps it linear on every platform alike.
   *
   *  The line ends are the ones `OffsetPosition` recognizes, and the search is its
   *  search, so a position from the index and one from the library agree. */
  private class LineIndex(val source: CharSequence) {

    /** Every line start, preceded by 0 and followed by the length — so that the line
     *  holding an offset always has both a start and an end to read. */
    private val starts: Array[Int] = {
      val buf = new ListBuffer[Int]
      var i   = 0

      buf += 0

      while (i < source.length) {
        val c = source.charAt(i)

        if (c == '\n' || (c == '\r' && (i == source.length - 1 || source.charAt(i + 1) != '\n')))
          buf += i + 1

        i += 1
      }

      buf += source.length
      buf.toArray
    }

    /** The 1-based line holding `offset`. */
    def lineOf(offset: Int): Int = {
      var lo = 0
      var hi = starts.length - 1

      while (lo + 1 < hi) {
        val mid = lo + ((hi - lo) / 2)

        if (offset < starts(mid)) hi = mid
        else lo = mid
      }

      lo + 1
    }

    /** The 1-based column of `offset`, given the line `lineOf` put it on. */
    def columnOf(offset: Int, line: Int): Int = offset - starts(line - 1) + 1

    /** The text of `line`, without whatever ended it. */
    def contentsOf(line: Int): String = {
      val from = starts(line - 1)
      var end  = starts(line)

      while (end > from && { val c = source.charAt(end - 1); c == '\n' || c == '\r' })
        end -= 1

      source.subSequence(from, end).toString
    }
  }

  /** A token's position.
   *
   *  It wrapped the position the character reader supplied, reading its line and column
   *  eagerly — which is where the name comes from, and which is what `LineIndex` exists
   *  to make cheap. Both spellings are kept because a reader that is not backed by a
   *  character sequence has no index to be read from. */
  class PositionWrapper private[indentation] (
      val line: Int,
      val column: Int,
      contents: () => String,
  ) extends Position {

    def this(p: Position) = this(p.line, p.column, () => p.longString.split("\n")(0))

    protected lazy val lineContents: String = contents()
  }

  private object IndentationParser extends Parser[Token] {
    def apply(in: Reader[Char]): ParseResult[Token] = {
      def indents(ch: Char, c: Int, r: Reader[Char]): (Int, Reader[Char]) =
        if (ch != ' ' && ch != '\t' || r.atEnd || r.first != ch)
          (c, r)
        else
          indents(ch, c + 1, r.rest)

      state match {
        case BLOCK_STATE =>
          // Drain dedents when a triggered block body is about to be closed by the
          // matching close-line-joining delimiter on the body's same indent line
          // (e.g. `f((x) -> \n var acc = 0 \n acc + 1)` — the `)` terminates the
          // body before being processed as the call's close). Without this, the
          // block-statement parser would never see its terminating Dedent.
          // Only drain when we're at the body's *outer* level (lineJoining == 0
           // inside the suspended frame). When we're inside nested parens within
           // the body (e.g. `p(inp)` mid-body), lineJoining > 0 and the
           // close-delim belongs to the inner pair, not the body terminator.
           // We drain on the matching close-delim (`)` / `]` / `}`) and on `,`
           // (the next-arg separator of the enclosing call/tuple) — both signal
           // end-of-body when seen at the body's outer level.
          if (!in.atEnd && joiningFrames.nonEmpty
              && lineJoining == 0
              && level.top > joiningFrames.top._2
              && (endLineJoiningChars.contains(in.first) || in.first == ',')) {
            current = joiningFrames.top._2
            state = DEDENT_STATE
            drainDedent = true
            level.pop()
            maybeRestoreJoiningFrame()
            return Success(Newline, in)
          }
          if (in.atEnd || in.first != '\n')
            Failure(null, in)
          else {
            // Block-trigger handling: if `lineJoining > 0` (we're inside parens) but
            // the most recently emitted token is the configured trigger (e.g. `->`),
            // suspend line-joining for the body. Push a frame so we can restore it
            // when dedent brings indentation back to (or below) the trigger's level.
            val triggered = lineJoining > 0 && triggerKeyword.exists(tk => lastEmittedToken == tk)
            // Trailing-token continuation: outside any paren/bracket/brace pair, a
            // trailing operator (per `isLineContinuationToken`) suppresses the
            // implicit newline so the RHS can live on the next indented line.
            val trailingContinuation =
              lineJoining == 0 && lastEmittedToken != null && isLineContinuationToken(lastEmittedToken)
            // Leading-token continuation: the next line begins with something that
            // can only belong to this one (per `isLineContinuationStart`), so the
            // newline is suppressed just as a trailing operator would have. Decided
            // before the block-trigger frame is pushed, so that a continued line is
            // never mistaken for the opening of a triggered body.
            val leadingContinuation =
              !in.rest.atEnd && isLineContinuationStart(skipLinePrefix(skipBlankLines(in.rest)))
            if ((lineJoining > 0 && !triggered) || trailingContinuation || leadingContinuation)
              Failure(null, in)
            else {
              if (triggered) {
                joiningFrames.push((lineJoining, level.top))
                lineJoining = 0
              }
              val in1 = skipBlankLines(in.rest)

              if (in1.atEnd) {
                Failure(null, in1)
              } else {
                val (c, r) = indents(in1.first, 0, in1)

                if (!samePlace(skipSpace(in1), r))
                  Error("only tabs or spaces (but not both on a given line) may be used for indentation", in1)
                else {
                  if (c > level.top) {
                    level.push(c)

                    if (newlineBeforeIndent) {
                      state = INDENT_STATE
                      Success(Newline, r)
                    } else {
                      Success(Indent, r)
                    }
                  } else if (c < level.top) {
                    current = c
                    state = DEDENT_STATE
                    level.pop()
                    maybeRestoreJoiningFrame()
                    Success(Newline, r)
                  } else {
                    Success(Newline, r)
                  }
                }
              }
            }
          }
        case INDENT_STATE =>
          state = BLOCK_STATE
          Success(Indent, in)
        case DEDENT_STATE =>
          // For drain-triggered dedents we skip the post-Dedent Newline because the
          // matching close-delim is the very next character — there is no physical
          // newline to represent.
          if (newlineAfterDedent && !drainDedent)
            state = NEWLINE_STATE
          else {
            if (current < level.top) {
              level.pop()
              maybeRestoreJoiningFrame()
            } else
              state = BLOCK_STATE
            if (drainDedent && state == BLOCK_STATE)
              drainDedent = false
          }

          Success(Dedent, in)
        case NEWLINE_STATE =>
          if (current < level.top) {
            state = DEDENT_STATE
            level.pop()
            maybeRestoreJoiningFrame()
          } else
            state = BLOCK_STATE

          Success(Newline, in)
      }
    }
  }
}
