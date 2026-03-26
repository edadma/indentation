# Indentation

![Maven Central](https://img.shields.io/maven-central/v/io.github.edadma/indentation_sjs1_3)
[![Last Commit](https://img.shields.io/github/last-commit/edadma/indentation)](https://github.com/edadma/indentation/commits)
![GitHub](https://img.shields.io/github/license/edadma/indentation)
![Scala Version](https://img.shields.io/badge/Scala-3.8.2-blue.svg)
![ScalaJS Version](https://img.shields.io/badge/Scala.js-1.20.2-blue.svg)
![Scala Native Version](https://img.shields.io/badge/Scala_Native-0.5.10-blue.svg)

A Scala library for indentation-sensitive lexical analysis using parser combinators. Extends `StdLexical` to automatically generate `INDENT`, `DEDENT`, and `NEWLINE` tokens for Python-style block structure.

## Installation

```scala
libraryDependencies += "io.github.edadma" %%% "indentation" % "0.0.2"
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

## Building

```bash
sbt compile                     # All platforms
sbt indentationJVM/compile      # JVM only
sbt indentationJVM/test         # Run tests
```

## License

ISC License -- see [LICENSE](LICENSE) for details.
