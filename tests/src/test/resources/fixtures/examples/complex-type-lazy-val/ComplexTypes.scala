object ComplexTypes:
  lazy val tuple: (Int, String, Double) = (42, "test", 3.14)
  lazy val func: Int => String = (x: Int) => s"number: $x"
  lazy val nested: List[Map[String, Int]] = List(Map("a" -> 1), Map("b" -> 2))

@main def main(): Unit =
  println(s"tuple = ${ComplexTypes.tuple}")
  println(s"func(5) = ${ComplexTypes.func(5)}")
  println(s"nested = ${ComplexTypes.nested}")
