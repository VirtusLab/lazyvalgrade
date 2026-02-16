class Secret:
  private lazy val secret = "hidden"
  def reveal = secret

class Base:
  protected lazy val protectedVal = "protected"

class Derived extends Base:
  def access = protectedVal

@main def main() =
  val s = new Secret()
  println(s.reveal)
  val d = new Derived()
  println(d.access)
