package io.github.edadma.indentation

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
 *  needs. */
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

  private def skipToEOL(r: Reader[Char]) = skip(r, a => a.atEnd || a.first == '\n')

  private def skipBlankLines(r: Reader[Char]): Reader[Char] =
    if (r.atEnd)
      r
    else {
      val r1 = skipSpace(r)

      if (r1.atEnd)
        r1
      else if (r1.first == '\n')
        skipBlankLines(r1.rest)
      else if (matches(r1, lineComment)) {
        val r2 = skipToEOL(r1.drop(lineComment.length))

        if (r2.atEnd)
          r2
        else
          skipBlankLines(r2.rest)
      } else if (matches(r1, blockCommentStart)) {
        val r2 = skip(r1.drop(blockCommentStart.length), a => matches(a, blockCommentEnd))

        if (r2.atEnd) sys.error("unclosed comment " + r1.pos)

        skipBlankLines(r2.drop(blockCommentEnd.length))
      } else
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
    new IndentationScanner(skipBlankLines(in))
  }

  override def whitespaceChar = elem("space char", c => c == ' ' || c == '\t' || c == '\r')

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
            case Failure(_, _) | Error(_, _) =>
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
                    case ns: NoSuccess => (errorToken(ns.msg), ns.next, skip(ns.next))
                  }
                case ns: NoSuccess => (errorToken(ns.msg), ns.next, skip(ns.next))
              }
          }
        case ns: NoSuccess => (errorToken(ns.msg), ns.next, skip(ns.next))
      }
    }

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

    lazy val pos = new PositionWrapper(rest1.pos)
  }

  class PositionWrapper(p: Position) extends Position {
    val column                      = p.column
    val line                        = p.line
    protected lazy val lineContents = p.longString.split("\n")(0)
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
            if ((lineJoining > 0 && !triggered) || trailingContinuation)
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

                if (skipSpace(in1).pos != r.pos)
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
