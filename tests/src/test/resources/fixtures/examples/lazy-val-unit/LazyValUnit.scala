object LazyUnit:
  var sideEffect = 0
  lazy val doStuff: Unit =
    sideEffect += 1

@main def main() =
  LazyUnit.doStuff
  LazyUnit.doStuff
  println(s"sideEffect = ${LazyUnit.sideEffect}")
