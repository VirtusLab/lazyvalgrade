object Types:
  opaque type Name = String
  object Name:
    def apply(s: String): Name = s

  lazy val defaultName: Name = Name("opaque")

@main def main() =
  println(s"name = ${Types.defaultName}")
