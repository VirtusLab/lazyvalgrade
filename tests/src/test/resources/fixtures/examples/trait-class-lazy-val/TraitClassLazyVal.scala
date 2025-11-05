trait Bar:
  lazy val b = "trait-test"

class Foo extends Bar

@main def main() =
  val foo = new Foo()
  println(foo.b)
