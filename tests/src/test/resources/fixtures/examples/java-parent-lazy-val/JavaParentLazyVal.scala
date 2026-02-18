class ScalaList extends java.util.ArrayList[String]:
  lazy val cachedSize: Int = size()

@main def main() =
  val l = new ScalaList()
  l.add("hello")
  println(s"cachedSize = ${l.cachedSize}")
