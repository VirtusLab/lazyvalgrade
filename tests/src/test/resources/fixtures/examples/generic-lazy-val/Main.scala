class Container[T](value: T):
  lazy val stored: T = value

@main def main() =
  val c = new Container[String]("test")
  println(c.stored)
