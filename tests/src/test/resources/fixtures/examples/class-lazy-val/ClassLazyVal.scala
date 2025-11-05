class Foo:
  lazy val a = "test"

@main def main() =
  val foo = new Foo()
  println(foo.a)
