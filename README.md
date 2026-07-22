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
libraryDependencies += "io.github.edadma" %%% "indentation" % "0.0.3"
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
| `blockTriggerToken` | Optional token that opens an indented block even inside a line-joining context (e.g., `Some("->")`). Default `None` — see [Block Trigger Token](#block-trigger-token) |

## Token Types

The lexer generates three special tokens in addition to the standard `StdLexical` tokens:

- `Newline` -- end of a logical line (same indentation level)
- `Indent` -- indentation increased
- `Dedent` -- indentation decreased

Indents and dedents are always balanced. The lexer handles:

- Tab or space indentation (but not mixed on the same line)
- Blank lines and comment-only lines (skipped)
- Line joining inside brackets/parentheses
- Proper dedent generation at end of input

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

## Building

```bash
sbt compile                     # All platforms
sbt indentationJVM/compile      # JVM only
sbt indentationJVM/test         # Run tests
```

## License

ISC License -- see [LICENSE](LICENSE) for details.
