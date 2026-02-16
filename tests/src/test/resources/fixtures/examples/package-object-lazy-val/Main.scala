package object foo:
  lazy val packageValue: String = "pkg-value"

@main def main() =
  println(foo.packageValue)
