object LazyValNullOption:
  var evalCount = 0
  lazy val nullValue: String =
    evalCount += 1
    null
  lazy val noneValue: Option[Int] = None
  lazy val someValue: Option[String] = Some("hello")

@main def main(): Unit =
  println(s"nullValue = ${LazyValNullOption.nullValue}")
  println(s"nullValue = ${LazyValNullOption.nullValue}")
  println(s"evalCount = ${LazyValNullOption.evalCount}")
  println(s"noneValue = ${LazyValNullOption.noneValue}")
  println(s"someValue = ${LazyValNullOption.someValue}")
