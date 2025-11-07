class Foo:
  lazy val instanceVal = "instance"

object Foo:
  lazy val objectVal = "object"

@main def main() =
  println(s"${Foo.objectVal}, ${new Foo().instanceVal}")
