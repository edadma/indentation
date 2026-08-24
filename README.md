# Indentation

![Maven Central](https://img.shields.io/maven-central/v/io.github.edadma/indentation_sjs1_3)
[![Last Commit](https://img.shields.io/github/last-commit/edadma/indentation)](https://github.com/edadma/indentation/commits)
![GitHub](https://img.shields.io/github/license/edadma/indentation)
![Scala Version](https://img.shields.io/badge/Scala-3.8.4-blue.svg)
![ScalaJS Version](https://img.shields.io/badge/Scala.js-1.21.0-blue.svg)
![Scala Native Version](https://img.shields.io/badge/Scala_Native-0.5.12-blue.svg)

A Scala library for indentation-sensitive lexical analysis using parser combinators. Extends `StdLexical` to automatically generate `INDENT`, `DEDENT`, and `NEWLINE` tokens for Python-style block structure.

## Installation

```scala
libraryDependencies += "io.github.edadma" %%% "indentation" % "0.0.10"
```

Cross-compiled for JVM, Scala.js, and Scala Native.

## Quick Start

```scala
import io.github.edadma.indentation.IndentationLexical
import scala.util.parsing.combinator.syntactical.StandardTokenParsers
import scala.util.parsing.input.CharSequenceReader

class MyParser extends StandardTokenParsers {
  override val lexical: IndentationLexical = new IndentationLexical(
    newlineBeforeIndent = true,
    newlineAfterDedent = true,
    startLineJoining = List("(", "["),
    endLineJoining = List(")", "]"),
    lineComment = "//",
    blockCommentStart = "/*",
    blockCommentEnd = "*/"
  ) {
    reserved ++= List("if", "else", "then", "print")
    delimiters ++= List("=", "+", "-", "*", "/", "<", ">", "(", ")")
  }

  import lexical.{Newline, Indent, Dedent}

  lazy val program: Parser[List[String]] =
    repsep(statement, rep1(Newline)) <~ opt(rep(Newline))

  lazy val statement: Parser[String] =
    "if" ~> ident ~ ("then" ~> block) ^^ { case id ~ _ => s"if($id)" } |
      "print" ~> ident ^^ (id => s"print($id)") |
      ident ~ ("=" ~> ident) ^^ { case a ~ b => s"$a=$b" }

  lazy val block: Parser[List[String]] =
    Newline ~> Indent ~> repsep(statement, rep1(Newline)) <~ opt(Newline) <~ Dedent

  def parse(input: String) =
    phrase(program)(lexical.read(new CharSequenceReader(input)))
}
```

Usage:

```scala
val parser = new MyParser
parser.parse("x = y\nif x then\n    print x\nprint done")
// Success(List(x=y, if(x), print(done)), ...)
```

Note: create a new parser instance per parse call, as `StandardTokenParsers` has internal mutable state.

## Configuration Options

| Parameter | Description |
|---|---|
| `newlineBeforeIndent` | Emit a Newline token before each Indent token |
| `newlineAfterDedent` | Emit a Newline token after each Dedent token |
| `startLineJoining` | Tokens that suppress indentation (e.g., `"("`, `"["`) |
| `endLineJoining` | Tokens that end line joining (e.g., `")"`, `"]"`) |
| `lineComment` | Line comment prefix (e.g., `"//"`, `"#"`) |
| `blockCommentStart` | Block comment start delimiter (e.g., `"/*"`) |
| `blockCommentEnd` | Block comment end delimiter (e.g., `"*/"`) |
| `blockTriggerToken` | Optional single token that opens an indented block even inside a line-joining context (e.g., `Some("->")`). Default `None`. For more than one, override `isBlockTrigger` — see [Block Trigger Token](#block-trigger-token) |

## Token Types

The lexer generates three special tokens in addition to the standard `StdLexical` tokens:

- `Newline` -- end of a logical line (same indentation level)
- `Indent` -- indentation increased
- `Dedent` -- indentation decreased

Indents and dedents are always balanced. The lexer handles:

- Tab or space indentation — different lines may use different characters, but mixing them within
  one line's indentation produces an `ErrorToken` positioned at the start of that line
- Blank lines and comment-only lines (skipped)
- Line joining inside brackets/parentheses
- Proper dedent generation at end of input

## Comments

Comments use the configured `lineComment`, `blockCommentStart` and `blockCommentEnd` in every
position — at the start of a line and in the middle of one alike.

**Block comments nest.** That is the point of a block comment: commenting out a region that already
contains one.

```
/* outer /* inner */ still commented out */
```

Nothing inside a block comment is otherwise interpreted — a line comment does not terminate it, and
a quote does not begin a string literal:

```
/* ;; the */ below still closes this comment */
```

Conversely, a block-comment delimiter inside a string literal or a line comment opens and closes
nothing:

```
path = "/* not a comment */"
```

A line comment runs to the end of its line; a block comment may span newlines, joining the lines it
spans into one logical line.

A comment is invisible to the off-side rule. A line whose code is preceded by a block comment is
indented by its own leading whitespace, not by wherever the comment happens to end:

```
if x
  /* note */ y
  z
```

`y` and `z` are both at indentation 2, so they are one block.

**An unterminated block comment produces an `ErrorToken` positioned at its opening delimiter**, so a
downstream compiler can report it as a diagnostic with a caret, rather than raising an exception. The
remainder of the input is consumed as comment text, so exactly one diagnostic is produced.

### Keeping the comments — `comment`

A comment is trivia and is dropped. Override `comment` to be told about each one as it is consumed,
in both positions, without changing anything else:

```scala
override protected def comment(from: Reader[Char], to: Reader[Char]): Unit =
  seen(from.offset) = from.source.subSequence(from.offset, to.offset).toString
```

It exists for the language that wants its comments for something *beside* parsing — a documentation
generator, an editor's hover text, a formatter that has to put them back. **No token is emitted and
nothing reaches the token stream**, so a lexer that ignores this hook behaves exactly as it did
before the hook existed.

Three things about the signature, each of which is the reason it is not something simpler:

- **It hands over the two readers rather than the text.** The default takes no substring, so a lexer
  that does not want comments pays nothing — the same property that makes `isBlockTrigger` free when
  it is not overridden.
- **Neither `offset` nor `source` is guaranteed by `Reader`.** They are there on
  `CharSequenceReader`, which is what almost everything uses; a lexer built over some other reader
  has to answer for itself, and handing over the readers is what lets it. The library guards the same
  methods internally for the same reason.
- **The same comment can be reported more than once, so record it idempotently — key by
  `from.offset` rather than appending to a list.** Deciding whether a line continues the one above
  runs the line-prefix skip over the next line *before* that line is scanned for real, and the
  argument is evaluated whatever `isLineContinuationStart` answers — so this happens for every
  lexer, not only one using leading continuations.

## What is covered by tests

The hooks are documented above and in the scaladoc, and *documented* is not the same as *exercised*.
This table says which is which, so nobody has to read the suite to find out:

| feature | suite |
|---|---|
| indentation, line joining, comments as trivia | `IndentationLexicalTests` |
| token positions, line contents | `PositionTests` |
| re-entrancy | `ReentrancyTests` |
| `blockTriggerToken` / `isBlockTrigger` | `BlockTriggerTests` |
| `isLineContinuationStart` / `previousToken` | `LeadingContinuationTests` |
| `comment` | `CommentHookTests` |
| the whole thing, through a small language | `ToyLanguageTests` |

`BlockTriggerTests` is the one that was added late: `blockTriggerToken` shipped with a docstring, a
README section and no test at all, and stayed that way until the feature was generalised. **A hook
that is reachable and unexercised is the failure mode this library has**, so a new one is not
finished until it has a row here.

The comment paths themselves are the counter-example and are worth naming as one, because it is easy
to assume otherwise about a feature that only just grew a hook: `IndentationLexicalTests` has covered
them since long before `comment` existed, exercising nesting, spanning lines and unterminated
comments **in both scan positions on purpose** — its own comment says why. What `CommentHookTests`
adds is coverage of the hook, not of the scanning underneath it.

## Line Joining

Expressions inside parentheses or brackets can span multiple lines without generating indentation tokens:

```
result = (1 +
          2 +
          3)
```

Configure which tokens trigger line joining via `startLineJoining` and `endLineJoining`.

## Block Trigger Token

Line joining normally suppresses *all* indentation tokens inside brackets — which is a problem
when a construct legitimately opens an indented block *within* a bracketed context. The classic
case is a multi-statement closure body passed as an argument:

```
f((x) ->
    var acc = 0
    acc + 1)
```

Because the body sits inside `(`...`)`, line joining would suppress the `Newline`/`Indent`/
`Dedent` tokens that a block-statement parser needs, and the body cannot parse.

Setting `blockTriggerToken` opts into a fix. When the configured token (here `"->"`) is the most
recently emitted token and a newline follows, the lexer **suspends line joining** for the extent
of the block body and resumes normal `Newline`/`Indent`/`Dedent` emission. When dedenting returns
to the trigger's indentation level, line joining is restored so the rest of the enclosing
argument list parses normally.

```scala
new IndentationLexical(
  newlineBeforeIndent = true,
  newlineAfterDedent = true,
  startLineJoining = List("(", "["),
  endLineJoining = List(")", "]"),
  lineComment = "//",
  blockCommentStart = "/*",
  blockCommentEnd = "*/",
  blockTriggerToken = Some("->")
)
```

Default is `None`, in which case the feature is entirely inert.

### More than one of them

Most languages have several tokens that open a block — an arrow opens a closure's body and a
`match` opens its arms — and a rule admitting only one of them is a rule nobody can state: a
reader has to remember which block forms may be written as an argument and which may not.
Override `isBlockTrigger` to say the useful thing instead, which is that **a token that opens a
block opens one wherever it is written**:

```scala
new IndentationLexical(/* ... */) {
  override protected def isBlockTrigger(tok: Token): Boolean = tok match {
    case Keyword("->") | Keyword("match") => true
    case _                                => false
  }
}
```

`blockTriggerToken` is what the predicate defaults to, so passing the token and overriding the
predicate are two spellings of the same feature and a lexer that does neither is unaffected.

Accept only tokens that genuinely cannot *end* an expression, for the reason bracket joining
exists in the first place: a token that could finish one would make the next line's margin
significant in a place where a reader is entitled to lay an argument list out however reads best.

## Trailing-Operator Continuation

Bracket line joining handles continuation driven by an *enclosing pair*. The opposite case —
continuation driven by the *trailing token* — is available by overriding
`isLineContinuationToken`:

```scala
new IndentationLexical(/* ... */) {
  private val opChars = Set('+', '-', '*', '/', '<', '>', '=', '&', '|', '^')

  override protected def isLineContinuationToken(tok: Token): Boolean = tok match {
    case k: Keyword => k.chars.nonEmpty && k.chars.forall(opChars.contains)
    case _          => false
  }
}
```

When a token satisfying this predicate appears immediately before a newline **outside** any
bracket pair, the implicit `Newline` (and any following indentation change) is suppressed, so the
next line continues the current expression:

```
total = a +
        b
```

The default implementation returns `false`, so only bracket line joining applies.

**Choose the token set carefully.** Return `true` only for tokens whose presence at end-of-line
unambiguously means "right-hand side follows." Do **not** include tokens that can legitimately
end a statement (such as postfix `++` / `--`), or tokens that drive their own indented-block
construct (such as `=`, `->`, `=>`) — those would swallow the block's `Newline`.

## Leading-Token Continuation

The mirror image, and the one a fluent API actually needs: a chain is habitually broken **before**
the dot rather than after it, so there is no trailing token to see and the decision has to be made
by looking ahead. Override `isLineContinuationStart`:

```scala
import scala.util.parsing.input.Reader

new IndentationLexical(/* ... */) {
  override protected def isLineContinuationStart(r: Reader[Char]): Boolean =
    !r.atEnd && r.first == '.' && !r.rest.atEnd && r.rest.first.isLetter
}
```

The reader is positioned at the first character of *code* on the next line — leading whitespace,
blank lines and comments have already been stepped over — so an implementation reads the line's
opening characters and nothing else. Returning `true` suppresses the `Newline` and any `Indent` or
`Dedent` the line's margin would have produced:

```
val view = text(label)
    .padding(8)
    .background(blue)
```

As with a trailing operator, a continuation line's own indentation carries no meaning, so it may be
laid out however reads best.

**Be sure the text you accept can never begin a statement.** The line is joined to the one above and
its margin is discarded, so a leading token that could also *open* a statement would pull a line
written at the outer margin into the block above it, and that block would then end one line later
than it looks like it does. A `.` followed by a name is the safe case — `..`, `...` and `.0` are all
excluded by requiring the letter, and no expression grammar begins a statement with a dot. A bare
`-` is **not** safe, since a statement may begin with a negation.

The default implementation returns `false`, so a lexer that does not override it is unaffected.

### Both ends of the join

Lookahead alone is not always enough. `previousToken` is the last token emitted before the newline
being decided (`null` at the start of input), so a predicate can require that the line above could
have *finished* an expression before joining the line below to it:

```scala
override protected def isLineContinuationStart(r: Reader[Char]): Boolean =
  !r.atEnd && r.first == '.' && !r.rest.atEnd && r.rest.first.isLetter &&
    previousToken != Keyword("match")
```

This matters in a language where a block body can itself begin with a dot. Without the guard,

```
value match
    .Red -> 1
```

reads the arm as a continuation of the header, because a leading dot is a leading dot whichever
construct it is under. Declining after the keyword that opens the block is the narrowest fix, and it
costs nothing: no call chain continues from `match`.

`previousToken` is the exact dual of `isLineContinuationToken`, so a predicate consulting both ends
is stating one rule rather than two — a line joins when what follows demands it *and* what precedes
admits it.

## Building

```bash
sbt compile                     # All platforms
sbt indentationJVM/compile      # JVM only
sbt indentationJVM/test         # Run tests
```

## License

ISC License -- see [LICENSE](LICENSE) for details.
