class Outer:
  lazy val outerVal: String = "outer"

  class Inner:
    lazy val innerVal: String = "inner"

  def getInner = new Inner()

@main def main() =
  val outer = new Outer()
  println(s"${outer.outerVal}, ${outer.getInner.innerVal}")
