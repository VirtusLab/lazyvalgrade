object Concurrent:
  @volatile var initCount = 0
  lazy val value: Int =
    initCount += 1
    Thread.sleep(10)
    42

@main def main() =
  val threads = (1 to 10).map { _ =>
    val t = new Thread(new Runnable { def run(): Unit = { val _ = Concurrent.value } })
    t
  }
  threads.foreach(_.start())
  threads.foreach(_.join())
  println(s"value = ${Concurrent.value}")
  println(s"initCount = ${Concurrent.initCount}")
