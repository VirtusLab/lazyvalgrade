  Current Coverage

  1. ✅ Simple object with lazy val
  2. ✅ Standalone class with lazy val
  3. ✅ Companion object with lazy val (object has lazy val)
  4. ✅ Companion class with lazy val (class has lazy val)
  5. ✅ Abstract class with lazy val
  6. ✅ Trait with lazy val
  7. ✅ No lazy val (control case)
  8. ✅ Multiple Lazy Vals in Same Class/Object
  9. ✅ Both companion class and object have lazy vals (complex patching scenario)
  10. ✅ Lazy val chain (tests initialization order)
  11. ✅ Private/protected lazy vals (access modifier handling)
  12. ✅ Generic lazy val (type parameter handling)
  13. ✅ Nested classes with lazy vals (scope handling)
  14. ✅ Mixed vals (distinguishing lazy from eager)
  15. ✅ Deeply nested objects with lazy vals (nested singleton scope handling)
  16. ✅ Enum with lazy val (Scala 3 enum class handling)
  17. ✅ Anonymous class with lazy val (anonymous class scope handling)
  18. ✅ Package object with lazy val (package$ compilation unit handling)

  Additional Test Patterns to Consider

  1. Multiple Lazy Vals in Same Class/Object ✅ DONE

  // multiple-lazy-vals-object/
  object MultiLazy:
    lazy val first: String = "one"
    lazy val second: Int = 42
    lazy val third: Double = 3.14

  @main def main() =
    println(s"${MultiLazy.first}, ${MultiLazy.second}, ${MultiLazy.third}")

  2. Lazy Val with Complex Initialization

  // complex-initialization/
  object Complex:
    lazy val computed: Int = {
      val x = 10
      val y = 20
      x + y * 2
    }

  @main def main() =
    println(Complex.computed)

  3. Nested Classes with Lazy Vals

  // nested-class-lazy-val/
  class Outer:
    lazy val outerVal = "outer"

    class Inner:
      lazy val innerVal = "inner"

    def getInner = new Inner()

  @main def main() =
    val outer = new Outer()
    println(s"${outer.outerVal}, ${outer.getInner.innerVal}")

  4. Lazy Val Referencing Another Lazy Val ✅ DONE

  // lazy-val-chain/
  object Chain:
    lazy val first: Int = 10
    lazy val second: Int = first * 2
    lazy val third: Int = second + first

  @main def main() =
    println(Chain.third)

  5. Lazy Val with Generic Type ✅ DONE

  // generic-lazy-val/
  class Container[T](value: T):
    lazy val stored: T = value

  @main def main() =
    val c = new Container[String]("test")
    println(c.stored)

  6. Lazy Val in Case Class ✅ DONE

  // case-class-lazy-val/
  case class Person(name: String):
    lazy val greeting = s"Hello, $name"

  @main def main() =
    val p = Person("Alice")
    println(p.greeting)

  7. Lazy Val with Side Effects

  // lazy-val-side-effects/
  object SideEffect:
    var counter = 0
    lazy val value: Int = {
      counter += 1
      42
    }

  @main def main() =
    println(SideEffect.value)
    println(SideEffect.value)  // Should not increment counter again
    println(s"Counter: ${SideEffect.counter}")  // Should be 1

  8. Lazy Val Override in Subclass ✅ DONE

  // lazy-val-override/
  abstract class Base:
    lazy val value: String = "base"

  class Derived extends Base:
    override lazy val value: String = "derived"

  @main def main() =
    val d = new Derived()
    println(d.value)

  9. Companion Pair - Both Have Lazy Vals ✅ DONE 

  // companion-both-lazy-vals/
  class Foo:
    lazy val instanceVal = "instance"

  object Foo:
    lazy val objectVal = "object"

  @main def main() =
    println(s"${Foo.objectVal}, ${new Foo().instanceVal}")

  10. Private Lazy Val ✅ DONE

  // private-protected-lazy-val/
  class Secret:
    private lazy val secret = "hidden"
    def reveal = secret

  @main def main() =
    println(new Secret().reveal)

  11. Protected Lazy Val ✅ DONE

  // private-protected-lazy-val/
  class Base:
    protected lazy val protected_val = "protected"

  class Derived extends Base:
    def access = protected_val

  @main def main() =
    println(new Derived().access)

  12. Lazy Val with null/Option

  // lazy-val-option/
  object Optional:
    lazy val maybeValue: Option[String] = Some("test")
    lazy val noneValue: Option[Int] = None

  @main def main() =
    println(s"${Optional.maybeValue}, ${Optional.noneValue}")

  13. Lazy Val in Enum

  // enum-lazy-val/
  enum Color:
    case Red, Green, Blue
    lazy val hex: String = this match
      case Red => "#FF0000"
      case Green => "#00FF00"
      case Blue => "#0000FF"

  @main def main() =
    println(Color.Red.hex)

  14. Lazy Val with Thread-Safe Computation (stress test)

  // concurrent-lazy-val/
  object Concurrent:
    @volatile var initCount = 0
    lazy val value: Int = {
      initCount += 1
      Thread.sleep(10)
      42
    }

  @main def main() =
    val threads = (1 to 10).map(_ =>
      new Thread(() => println(Concurrent.value))
    )
    threads.foreach(_.start())
    threads.foreach(_.join())
    println(s"Init count: ${Concurrent.initCount}")  // Should be 1

  15. Lazy Val in Anonymous Class

  // anonymous-class-lazy-val/
  trait Processor:
    lazy val result: String

  @main def main() =
    val proc = new Processor:
      lazy val result = "anonymous"
    println(proc.result)

  16. Lazy Val with Exception in Initializer

  // lazy-val-exception/
  object Exceptional:
    lazy val failing: Int = throw new RuntimeException("oops")
    lazy val safe: String = "safe"

  @main def main() =
    println(Exceptional.safe)
    try {
      println(Exceptional.failing)
    } catch {
      case e: RuntimeException => println("Caught exception")
    }

  17. Mixed Lazy and Non-Lazy Vals

  // mixed-vals/
  class Mixed:
    val eager = "eager"
    lazy val lazy_val = "lazy"
    var mutable = "mutable"

  @main def main() =
    val m = new Mixed()
    println(s"${m.eager}, ${m.lazy_val}, ${m.mutable}")

  18. Lazy Val with by-name Parameter

  // lazy-val-by-name/
  class Deferred(byName: => String):
    lazy val stored = byName

  @main def main() =
    var counter = 0
    val d = new Deferred({counter += 1; "test"})
    println(d.stored)
    println(s"Counter: $counter")

  19. Deeply Nested Companion Objects

  // nested-companions/
  object Outer:
    object Inner:
      lazy val value = "nested"

  @main def main() =
    println(Outer.Inner.value)

  20. Lazy Val with Complex Type (Tuple, Function)

  // complex-type-lazy-val/
  object ComplexTypes:
    lazy val tuple: (Int, String, Double) = (42, "test", 3.14)
    lazy val func: Int => String = (x: Int) => s"number: $x"

  @main def main() =
    println(ComplexTypes.tuple)
    println(ComplexTypes.func(5))

  21. Self-Type with Lazy Val

  // self-type-lazy-val/
  trait Component:
    self: Logger =>
    lazy val name = "component"

  trait Logger:
    def log(msg: String): Unit

  class MyComponent extends Component with Logger:
    def log(msg: String) = println(msg)

  @main def main() =
    val c = new MyComponent()
    println(c.name)

  22. Lazy Val in Package Object

  // package-object-lazy-val/foo/package.scala
  package object foo:
    lazy val packageValue = "pkg-value"

  // package-object-lazy-val/Main.scala
  import foo.*

  @main def main() =
    println(packageValue)

  Priority Recommendations

  High Priority (Core Functionality):
  1. ✅ Multiple lazy vals (tests bitmap/offset indexing)
  2. ✅ Both companion class and object have lazy vals (complex patching scenario)
  3. ✅ Lazy val chain (tests initialization order)
  4. ✅ Case class lazy val (common pattern) - DONE
  5. ✅ Lazy val override (inheritance complexity) - DONE

  Medium Priority (Edge Cases):
  6. ✅ Private/protected lazy vals (access modifier handling) - DONE
  7. ✅ Generic lazy val (type parameter handling) - DONE
  8. ✅ Nested classes (scope handling) - DONE
  9. ✅ Mixed vals (distinguishing lazy from eager) - DONE

  Low Priority (Nice to Have):
  10. Complex types, enums, anonymous classes, exceptions
  11. Concurrent access (if we want to prove thread safety)