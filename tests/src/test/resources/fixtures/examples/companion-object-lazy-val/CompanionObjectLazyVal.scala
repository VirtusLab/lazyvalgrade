object Foo:
  lazy val a = "test"
class Foo

@main def main() =
  println(Foo.a)
