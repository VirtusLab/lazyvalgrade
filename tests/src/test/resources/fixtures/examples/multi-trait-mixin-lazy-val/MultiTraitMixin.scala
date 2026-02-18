trait HasName:
  lazy val name: String = "default-name"

trait HasAge:
  lazy val age: Int = 0

trait HasEmail:
  lazy val email: String = "none"

class Person extends HasName with HasAge with HasEmail

@main def main() =
  val p = new Person()
  println(s"${p.name}, ${p.age}, ${p.email}")
