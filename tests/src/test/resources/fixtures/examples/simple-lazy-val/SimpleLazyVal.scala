object SimpleLazyVal {
  lazy val simpleLazy: Int = 42

  def main(args: Array[String]): Unit = {
    println(s"simpleLazy = $simpleLazy")
  }
}
