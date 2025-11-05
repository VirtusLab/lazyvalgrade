abstract class Bar:
  lazy val b = "abstract-test"

class Foo extends Bar

@main def main() =
  val foo = new Foo()
  println(foo.b)
