package io.github.edadma.indentation

import ToyLanguageParser.{Failure, Success, Error}

import pprint.pprintln

@main def run(): Unit = {
  val input =
    """
      |x = 10
      |y = 20
      |if x < y then
      |    sum = x + y
      |    print sum
      |else
      |    diff = y - x
      |    print diff
      |""".stripMargin
//    """
//      |x = 10
//      |print x + 2
//      |if x > 0 then
//      |  print x
//      |else
//      |  print 0
//      |""".stripMargin
  val ast =
    ToyLanguageParser.parse(input) match {
      case Success(result, next) => result
      case Failure(_, _)         => ???
      case Error(_, _)           => ???
    }

  pprintln(ast)
  new ToyInterpreter().run(ast)
}
