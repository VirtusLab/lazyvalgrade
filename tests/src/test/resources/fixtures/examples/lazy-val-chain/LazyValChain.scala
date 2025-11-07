object Chain:
  lazy val first: Int = 10
  lazy val second: Int = first * 2
  lazy val third: Int = second + first

@main def main() =
  println(Chain.third)
