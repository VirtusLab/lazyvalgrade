class Deferred(byName: => String):
  lazy val stored: String = byName

@main def main(): Unit =
  var counter = 0
  val d = new Deferred({ counter += 1; s"evaluated-$counter" })
  println(s"stored = ${d.stored}")
  println(s"stored = ${d.stored}")
  println(s"counter = $counter")
