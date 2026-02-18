class Serializable extends java.io.Serializable:
  @transient lazy val cached: String = "not-serialized"
  lazy val persistent: String = "serialized"

@main def main() =
  val s = new Serializable()
  println(s"${s.cached}, ${s.persistent}")
