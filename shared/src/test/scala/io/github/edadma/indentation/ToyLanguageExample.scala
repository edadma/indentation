package io.github.edadma.indentation

@main def runExample(): Unit = {

  val parser = new ToyLanguageParser

  // Example 1: Simple program
  val program1 = """
    x = 10
    y = 20
    if x < y then
        sum = x + y
        print sum
    else
        diff = y - x  
        print diff
    print 999
  """.trim

  println("=== Example 1: Basic conditionals ===")
  println("Source:")
  println(program1)
  println("\nOutput:")

  parser.parse(program1) match {
    case parser.Success(ast, _) =>
      ToyInterpreter.run(ast)
    case parser.NoSuccess(msg, _) =>
      println(s"Parse error: $msg")
  }

  // Example 2: Nested conditions
  val program2 = """
    age = 25
    if age >= 18 then
        if age >= 65 then
            print 3
        else
            if age >= 21 then
                print 2
            else
                print 1
    else
        print 0
  """.trim

  println("\n=== Example 2: Nested conditions ===")
  println("Source:")
  println(program2)
  println("\nOutput:")

  parser.parse(program2) match {
    case parser.Success(ast, _) =>
      ToyInterpreter.run(ast)
    case parser.NoSuccess(msg, _) =>
      println(s"Parse error: $msg")
  }

  // Example 3: Complex expressions
  val program3 = """
    a = 2
    b = 3
    c = 4
    result = a + b * c
    if result > 10 then
        final = result * 2
        print final
    else
        final = result + 5
        print final
  """.trim

  println("\n=== Example 3: Complex expressions ===")
  println("Source:")
  println(program3)
  println("\nOutput:")

  parser.parse(program3) match {
    case parser.Success(ast, _) =>
      ToyInterpreter.run(ast)
    case parser.NoSuccess(msg, _) =>
      println(s"Parse error: $msg")
  }

  // Example 4: Line joining with parentheses
  val program4 = """
    x = (10 + 
         20 + 
         30)
    print x
  """.trim

  println("\n=== Example 4: Line joining ===")
  println("Source:")
  println(program4)
  println("\nOutput:")

  parser.parse(program4) match {
    case parser.Success(ast, _) =>
      ToyInterpreter.run(ast)
    case parser.NoSuccess(msg, _) =>
      println(s"Parse error: $msg")
  }

  // Example 5: Show token stream for debugging
  println("\n=== Example 5: Token stream for debugging ===")
  val simpleProgram = """
    if x > 0 then
        print x
    else
        print 0
  """.trim

  println("Source:")
  println(simpleProgram)
  println("\nTokens:")

  val tokens = parser.lexical.scan(simpleProgram)
  tokens.foreach(token => println(s"  $token"))
}
