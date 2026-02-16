object SideEffect:
  var counter = 0
  lazy val value: Int =
    counter += 1
    42

@main def main(): Unit =
  println(s"value = ${SideEffect.value}")
  println(s"value = ${SideEffect.value}")
  println(s"counter = ${SideEffect.counter}")
