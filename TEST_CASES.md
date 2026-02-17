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
  19. ✅ Lazy val with side effects (single-initialization verification)
  20. ✅ Lazy val with exception in initializer (retry semantics verification)
  21. ✅ Self-type with lazy val (self-type scope handling)
  22. ✅ Complex type lazy vals (Tuple, Function, nested generics erasure handling)
  23. ✅ Lazy val with by-name parameter (closure-based initializer handling)
  24. ✅ Lazy val with null/Option (sentinel value handling)
  25. ✅ Complex initialization (block body with loops, pattern matching, collections)

  Potential Breakers (Stress Tests)

  These target specific assumptions and limits in the patching implementation.

  HIGH RISK - Likely to break:

  1. Many Lazy Vals (33+ forcing second bitmap word)

  In 3.3-3.7, the bitmap is a Long (64 bits), 2 bits per lazy val state = 32 lazy vals per word.
  The 33rd lazy val needs a second bitmap field. The OFFSET$_m_N indexing (currently sequential
  0..N mapping to lazyVals list) might break when there are multiple bitmap words or when
  the compiler generates OFFSET fields differently for the second word.

  // many-lazy-vals/
  object ManyLazy:
    lazy val v01: Int = 1
    lazy val v02: Int = v01 + 1
    // ... chain up to v35
    lazy val v35: Int = v34 + 1

  @main def main() =
    println(s"v35 = ${ManyLazy.v35}")  // Forces all 35 lazy vals via chain

  2. Multiple Trait Mixin (each trait contributes lazy vals)

  When a class mixes in multiple traits that each define lazy vals, the field layout in the
  implementing class contains lazy vals from all traits. The OFFSET indices are assigned
  across all lazy vals in the class, but the patcher maps them positionally. If traits
  contribute lazy vals in an order the patcher doesn't expect (e.g., linearization order
  vs declaration order), the OFFSET-to-VarHandle mapping could be wrong.

  // multi-trait-mixin-lazy-val/
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

  3. Diamond Inheritance with Lazy Vals

  Diamond pattern where a lazy val is defined in a shared ancestor and potentially
  overridden or inherited through two paths. The linearization and mixin forwarder
  generation might create unusual OFFSET layouts.

  // diamond-lazy-val/
  trait Base:
    lazy val value: String = "base"

  trait Left extends Base:
    lazy val leftOnly: String = "left"

  trait Right extends Base:
    lazy val rightOnly: String = "right"

  class Diamond extends Left with Right

  @main def main() =
    val d = new Diamond()
    println(s"${d.value}, ${d.leftOnly}, ${d.rightOnly}")

  MEDIUM RISK - Might break:

  4. Lazy Val Returning Unit

  Unit erases to void but lazy val storage is Object-typed. The compiler boxes Unit as
  BoxedUnit in the storage field. The VarHandle compareAndSet might behave differently
  with BoxedUnit vs the Unsafe-based objCAS, especially around the sentinel/null check.

  // lazy-val-unit/
  object LazyUnit:
    var sideEffect = 0
    lazy val doStuff: Unit =
      sideEffect += 1

  @main def main() =
    LazyUnit.doStuff
    LazyUnit.doStuff
    println(s"sideEffect = ${LazyUnit.sideEffect}")

  5. Local Class with Lazy Val (class defined inside a method)

  A class defined inside a method gets an unusual class name (e.g., main$Foo$1) and
  captures the enclosing method's scope. The compiler might handle OFFSET field
  placement differently for local classes.

  // local-class-lazy-val/
  @main def main() =
    class Local:
      lazy val value: String = "local"
    val l = new Local()
    println(s"value = ${l.value}")

  6. Lazy Val with Opaque Type

  Opaque types change erasure behavior. The storage field descriptor might not be
  Ljava/lang/Object; if the opaque type erases to a primitive or specific class,
  which would break the detection check (requires Object descriptor for 3.3+).

  // opaque-type-lazy-val/
  object Types:
    opaque type Name = String
    object Name:
      def apply(s: String): Name = s

    lazy val defaultName: Name = Name("opaque")

  @main def main() =
    println(s"name = ${Types.defaultName}")

  7. Lazy Val in Class Extending Java Class

  If a Scala class extends a Java class (e.g., java.util.ArrayList), the field layout
  includes Java fields first. This might shift OFFSET calculations or interfere with
  the findVarHandle call if the Java parent has fields that affect layout.

  // java-parent-lazy-val/
  class ScalaList extends java.util.ArrayList[String]:
    lazy val cachedSize: Int = size()

  @main def main() =
    val l = new ScalaList()
    l.add("hello")
    println(s"cachedSize = ${l.cachedSize}")

  8. Concurrent Access (thread-safety stress test)

  The actual thread-safety semantics of VarHandle.compareAndSet vs Unsafe.objCAS.
  If the patching subtly changes the memory ordering or CAS semantics, concurrent
  access could expose it. Tests that the lazy val is initialized exactly once under
  contention.

  // concurrent-lazy-val/
  object Concurrent:
    @volatile var initCount = 0
    lazy val value: Int =
      initCount += 1
      Thread.sleep(10)
      42

  @main def main() =
    val threads = (1 to 10).map(_ =>
      new Thread(() => println(Concurrent.value))
    )
    threads.foreach(_.start())
    threads.foreach(_.join())
    println(s"initCount = ${Concurrent.initCount}")

  LOW RISK - Probably fine but worth checking:

  9. Lazy Val with @transient Annotation

  @transient affects serialization and might change field flags in bytecode,
  potentially confusing the volatile flag check in detection.

  // transient-lazy-val/
  class Serializable extends java.io.Serializable:
    @transient lazy val cached: String = "not-serialized"
    lazy val persistent: String = "serialized"

  10. Inline/Transparent Method Producing Lazy Val

  Scala 3 inline might affect how the initializer is compiled, potentially
  inlining the initialization code differently and breaking the lzyINIT
  method pattern match.

  // inline-lazy-val/
  inline def makeValue: String = "inlined"
  object InlineLazy:
    lazy val value: String = makeValue

  ----

  Completed Test Pattern Reference

  Original test pattern descriptions kept for reference. All of these are implemented above.

  1. Multiple Lazy Vals in Same Class/Object ✅ (#8)

  // multiple-lazy-vals-object/
  object MultiLazy:
    lazy val first: String = "one"
    lazy val second: Int = 42
    lazy val third: Double = 3.14

  @main def main() =
    println(s"${MultiLazy.first}, ${MultiLazy.second}, ${MultiLazy.third}")

  2. Lazy Val with Complex Initialization ✅ (#25)

  // complex-initialization/
  object Complex:
    lazy val computed: Int = {
      val x = 10
      val y = 20
      x + y * 2
    }

  @main def main() =
    println(Complex.computed)

  3. Nested Classes with Lazy Vals ✅ (#13)

  // nested-class-lazy-val/
  class Outer:
    lazy val outerVal = "outer"

    class Inner:
      lazy val innerVal = "inner"

    def getInner = new Inner()

  @main def main() =
    val outer = new Outer()
    println(s"${outer.outerVal}, ${outer.getInner.innerVal}")

  4. Lazy Val Referencing Another Lazy Val ✅ (#10)

  // lazy-val-chain/
  object Chain:
    lazy val first: Int = 10
    lazy val second: Int = first * 2
    lazy val third: Int = second + first

  @main def main() =
    println(Chain.third)

  5. Lazy Val with Generic Type ✅ (#12)

  // generic-lazy-val/
  class Container[T](value: T):
    lazy val stored: T = value

  @main def main() =
    val c = new Container[String]("test")
    println(c.stored)

  6. Lazy Val in Case Class ✅ (#2 via class-lazy-val)

  // case-class-lazy-val/
  case class Person(name: String):
    lazy val greeting = s"Hello, $name"

  @main def main() =
    val p = Person("Alice")
    println(p.greeting)

  7. Lazy Val with Side Effects ✅ (#19)

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

  8. Lazy Val Override in Subclass ✅ (#5)

  // lazy-val-override/
  abstract class Base:
    lazy val value: String = "base"

  class Derived extends Base:
    override lazy val value: String = "derived"

  @main def main() =
    val d = new Derived()
    println(d.value)

  9. Companion Pair - Both Have Lazy Vals ✅ (#9)

  // companion-both-lazy-vals/
  class Foo:
    lazy val instanceVal = "instance"

  object Foo:
    lazy val objectVal = "object"

  @main def main() =
    println(s"${Foo.objectVal}, ${new Foo().instanceVal}")

  10. Private Lazy Val ✅ (#11)

  // private-protected-lazy-val/
  class Secret:
    private lazy val secret = "hidden"
    def reveal = secret

  @main def main() =
    println(new Secret().reveal)

  11. Protected Lazy Val ✅ (#11)

  // private-protected-lazy-val/
  class Base:
    protected lazy val protected_val = "protected"

  class Derived extends Base:
    def access = protected_val

  @main def main() =
    println(new Derived().access)

  12. Lazy Val with null/Option ✅ (#24)

  // lazy-val-option/
  object Optional:
    lazy val maybeValue: Option[String] = Some("test")
    lazy val noneValue: Option[Int] = None

  @main def main() =
    println(s"${Optional.maybeValue}, ${Optional.noneValue}")

  13. Lazy Val in Enum ✅ (#16)

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

  15. Lazy Val in Anonymous Class ✅ (#17)

  // anonymous-class-lazy-val/
  trait Processor:
    lazy val result: String

  @main def main() =
    val proc = new Processor:
      lazy val result = "anonymous"
    println(proc.result)

  16. Lazy Val with Exception in Initializer ✅ (#20)

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

  17. Mixed Lazy and Non-Lazy Vals ✅ (#14)

  // mixed-vals/
  class Mixed:
    val eager = "eager"
    lazy val lazy_val = "lazy"
    var mutable = "mutable"

  @main def main() =
    val m = new Mixed()
    println(s"${m.eager}, ${m.lazy_val}, ${m.mutable}")

  18. Lazy Val with by-name Parameter ✅ (#23)

  // lazy-val-by-name/
  class Deferred(byName: => String):
    lazy val stored = byName

  @main def main() =
    var counter = 0
    val d = new Deferred({counter += 1; "test"})
    println(d.stored)
    println(s"Counter: $counter")

  19. Deeply Nested Companion Objects ✅ (#15)

  // nested-companions/
  object Outer:
    object Inner:
      lazy val value = "nested"

  @main def main() =
    println(Outer.Inner.value)

  20. Lazy Val with Complex Type (Tuple, Function) ✅ (#22)

  // complex-type-lazy-val/
  object ComplexTypes:
    lazy val tuple: (Int, String, Double) = (42, "test", 3.14)
    lazy val func: Int => String = (x: Int) => s"number: $x"

  @main def main() =
    println(ComplexTypes.tuple)
    println(ComplexTypes.func(5))

  21. Self-Type with Lazy Val ✅ (#21)

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

  22. Lazy Val in Package Object ✅ (#18)

  // package-object-lazy-val/foo/package.scala
  package object foo:
    lazy val packageValue = "pkg-value"

  // package-object-lazy-val/Main.scala
  import foo.*

  @main def main() =
    println(packageValue)