inline def makeValue: String = "inlined"

object InlineLazy:
  lazy val value: String = makeValue

@main def main() =
  println(s"value = ${InlineLazy.value}")
