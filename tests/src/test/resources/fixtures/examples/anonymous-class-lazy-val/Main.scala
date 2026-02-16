trait Processor:
  lazy val result: String

object App:
  val proc = new Processor:
    lazy val result: String = "anonymous"

@main def main() =
  println(App.proc.result)
