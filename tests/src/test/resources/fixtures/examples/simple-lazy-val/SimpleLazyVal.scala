object SimpleLazyVal:
  lazy val simpleLazy: Int = 42

@main def main: Unit =
  println(s"simpleLazy = ${SimpleLazyVal.simpleLazy}")
