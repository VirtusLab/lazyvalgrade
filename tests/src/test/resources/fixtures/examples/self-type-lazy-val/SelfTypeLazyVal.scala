trait Component:
  self: Logger =>
  lazy val name: String = "component"
  def greet: String = s"$name logging via ${log("hello")}"

trait Logger:
  def log(msg: String): String

class MyComponent extends Component with Logger:
  def log(msg: String): String = s"[LOG] $msg"

@main def main(): Unit =
  val c = new MyComponent()
  println(s"name = ${c.name}")
  println(c.greet)
