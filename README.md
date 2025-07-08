# Indentation

[![License: ISC](https://img.shields.io/badge/License-ISC-blue.svg)](https://opensource.org/licenses/ISC)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.edadma/indentation_3.svg)](https://search.maven.org/artifact/io.github.edadma/indentation_3)

A Scala library for indentation-sensitive lexical analysis using parser combinators.

## Overview

**Indentation** extends Scala's standard lexical analyzer to support Python-style indentation sensitivity. It automatically generates `INDENT`, `DEDENT`, and `NEWLINE` tokens, making it easy to build parsers for languages that use indentation for block structure.

Perfect for creating domain-specific languages, configuration parsers, or any language where you want clean, readable syntax without explicit block delimiters.

## Features

🔹 **Indentation-sensitive lexing** - Automatic `INDENT`/`DEDENT` token generation  
🔹 **Line joining** - Configurable bracket/parentheses handling across lines  
🔹 **Comment support** - Built-in line and block comment processing  
🔹 **Cross-platform** - Works on JVM, JavaScript, and Scala Native  
🔹 **Parser combinator integration** - Extends `StdLexical` seamlessly  
🔹 **Comprehensive testing** - Well-tested with edge cases covered

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies += "io.github.edadma" %%% "indentation" % "0.0.1"
```

For Maven:
```xml
<dependency>
  <groupId>io.github.edadma</groupId>
  <artifactId>indentation_3</artifactId>
  <version>0.0.1</version>
</dependency>
```

## Quick Start

```scala
import io.github.edadma.indentation.IndentationLexical

val lexical = new IndentationLexical(
  newlineBeforeIndent = false,
  newlineAfterDedent = true,
  startLineJoining = List("(", "["),
  endLineJoining = List(")", "]"),
  lineComment = "//",
  blockCommentStart = "/*",
  blockCommentEnd = "*/"
) {
  // Add your language-specific tokens
  reserved ++= List("if", "else", "then")
  delimiters ++= List("=", "+", "-", "(", ")")
}

// Scan some indented code
val tokens = lexical.scan("""
x = 1
if x > 0 then
    print x
    y = x + 1
print "done"
""")

// Results in: [Identifier("x"), Keyword("="), NumericLit("1"), Newline, 
//             Keyword("if"), Identifier("x"), Keyword(">"), NumericLit("0"), 
//             Keyword("then"), Newline, Indent, ...]
```

## Configuration Options

The `IndentationLexical` constructor accepts:

- **`newlineBeforeIndent`** - Insert newline before indent tokens
- **`newlineAfterDedent`** - Insert newline after dedent tokens
- **`startLineJoining`** - Tokens that start line joining (e.g., `"("`, `"["`)
- **`endLineJoining`** - Tokens that end line joining (e.g., `")"`, `"]"`)
- **`lineComment`** - Line comment prefix (e.g., `"//"`, `"#"`)
- **`blockCommentStart`** - Block comment start delimiter
- **`blockCommentEnd`** - Block comment end delimiter

## Line Joining

Line joining allows expressions to span multiple lines within parentheses or brackets without generating indentation tokens:

```scala
// This works without indentation tokens
result = (1 + 2 + 
          3 + 4)

coordinates = [
  x, y, z,
  a, b, c
]
```

## Complete Example

Here's a complete toy language parser using the indentation lexer:

```scala
import io.github.edadma.indentation.IndentationLexical
import scala.util.parsing.combinator.syntactical.StandardTokenParsers

// AST definitions
sealed trait Stmt
case class Assign(variable: String, value: Expr) extends Stmt
case class If(condition: Expr, thenBlock: List[Stmt], elseBlock: Option[List[Stmt]] = None) extends Stmt
case class Print(expr: Expr) extends Stmt

sealed trait Expr
case class Var(name: String) extends Expr
case class Num(value: Int) extends Expr
case class BinOp(left: Expr, op: String, right: Expr) extends Expr

object ToyParser extends StandardTokenParsers {
  override val lexical = new IndentationLexical(
    newlineBeforeIndent = true,
    newlineAfterDedent = true,
    startLineJoining = List("("),
    endLineJoining = List(")"),
    lineComment = "//",
    blockCommentStart = "/*", 
    blockCommentEnd = "*/"
  ) {
    reserved ++= List("if", "else", "then", "print")
    delimiters ++= List("=", "+", "-", "*", "/", "<", ">", "(", ")")
  }

  import lexical.{Newline, Indent, Dedent}

  def program: Parser[List[Stmt]] = rep(statement)
  
  def statement: Parser[Stmt] = 
    assignment | ifStatement | printStatement
    
  def assignment: Parser[Assign] =
    ident ~ "=" ~ expr <~ Newline ^^ { case id ~ _ ~ value => Assign(id, value) }
    
  def ifStatement: Parser[If] = 
    "if" ~ expr ~ "then" ~ Newline ~ Indent ~ rep1(statement) ~ Dedent ~ opt(elseClause) ^^ {
      case _ ~ condition ~ _ ~ _ ~ _ ~ thenStmts ~ _ ~ elseStmts =>
        If(condition, thenStmts, elseStmts)
    }
    
  // ... rest of parser
}

// Usage
val code = """
x = 10
if x > 5 then
    print x
    y = x * 2
    print y
else  
    print 0
"""

ToyParser.parse(code) match {
  case ToyParser.Success(ast, _) => println(s"Parsed: $ast")
  case failure => println(s"Parse failed: $failure")
}
```

This example demonstrates:
- Variable assignments
- If/else statements with proper indentation
- Expression parsing with operator precedence
- Block structure using `INDENT`/`DEDENT` tokens

## Token Types

The lexer generates these special tokens:

- **`Newline`** - End of logical line
- **`Indent`** - Increase in indentation level
- **`Dedent`** - Decrease in indentation level

Plus all standard tokens from `StdLexical`: identifiers, numeric literals, string literals, keywords, and delimiters.

## Building from Source

```bash
git clone https://github.com/edadma/indentation.git
cd indentation
sbt test           # Run tests
sbt compile        # Compile for JVM
sbt js/compile     # Compile for JavaScript  
sbt native/compile # Compile for Native
```

## Testing

The library includes comprehensive tests covering:

- Basic indentation/dedentation
- Line joining with parentheses and brackets
- Comment handling
- Mixed whitespace scenarios
- Edge cases and error conditions

Run tests with: `sbt test`

## Use Cases

Perfect for building parsers for:

- **Configuration languages** - YAML-style config files
- **Domain-specific languages** - Clean syntax for business rules
- **Template languages** - Indented template systems
- **Scripting languages** - Python-style scripting DSLs
- **Build files** - Indented build configurations

## Requirements

- Scala 3.7.1+
- `scala-parser-combinators` (automatically included)

## License

[ISC License](LICENSE) - see LICENSE file for details.

## Contributing

Contributions welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass
5. Submit a pull request

## Links

- [API Documentation](https://javadoc.io/doc/io.github.edadma/indentation_3)
- [Issue Tracker](https://github.com/edadma/indentation/issues)
- [Changelog](CHANGELOG.md)

---

*Making indentation-sensitive parsing simple and reliable.* 📝