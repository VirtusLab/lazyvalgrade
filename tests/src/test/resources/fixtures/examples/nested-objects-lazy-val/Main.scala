object Outer:
  lazy val outerVal: String = "outer"

  object Inner:
    lazy val innerVal: String = "inner"

@main def main() =
  println(s"${Outer.outerVal}, ${Outer.Inner.innerVal}")
