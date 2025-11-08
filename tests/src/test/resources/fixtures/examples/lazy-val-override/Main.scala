abstract class Base:
  lazy val value: String = "base"

class Derived extends Base:
  override lazy val value: String = "derived"

@main def main() =
  val d = new Derived()
  println(d.value)
