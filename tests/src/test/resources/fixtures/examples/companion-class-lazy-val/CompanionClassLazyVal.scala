class Foo:
  lazy val a = "test"

object Foo:
  def apply() = new Foo()

@main def main() =
  println(Foo().a)
