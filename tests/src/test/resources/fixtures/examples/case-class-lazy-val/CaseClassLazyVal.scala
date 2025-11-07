case class Person(name: String):
  lazy val greeting = s"Hello, $name"

@main def main() =
  val p = Person("Alice")
  println(p.greeting)
