trait Base:
  lazy val value: String = "base"

trait Left extends Base:
  lazy val leftOnly: String = "left"

trait Right extends Base:
  lazy val rightOnly: String = "right"

class Diamond extends Left with Right

@main def main() =
  val d = new Diamond()
  println(s"${d.value}, ${d.leftOnly}, ${d.rightOnly}")
