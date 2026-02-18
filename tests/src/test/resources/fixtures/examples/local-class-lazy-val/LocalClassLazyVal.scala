object LocalClassTest:
  def run(): String =
    class Local:
      lazy val value: String = "local"
    val l = new Local()
    l.value

@main def main() =
  println(s"value = ${LocalClassTest.run()}")
