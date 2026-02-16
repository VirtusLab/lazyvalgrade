class Mixed:
  val eager: String = "eager"
  lazy val lazyVal: String = "lazy"
  var mutable: String = "mutable"

@main def main() =
  val m = new Mixed()
  println(s"${m.eager}, ${m.lazyVal}, ${m.mutable}")
