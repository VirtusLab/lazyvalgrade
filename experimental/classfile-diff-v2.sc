//> using scala 3.7.3
//> using dep com.lihaoyi::os-lib:0.11.3
//> using dep org.ow2.asm:asm:9.7
//> using dep org.ow2.asm:asm-util:9.7
//> using dep org.ow2.asm:asm-tree:9.7
//> using dep com.outr::scribe:3.15.0
//> using file ../core/src/main/scala/lazyvalgrade/classfile/ClassfileModels.scala
//> using file ../core/src/main/scala/lazyvalgrade/classfile/OpcodeUtils.scala
//> using file ../core/src/main/scala/lazyvalgrade/classfile/ClassfileParser.scala
//> using file ../core/src/main/scala/lazyvalgrade/classfile/ClassfileComparator.scala

import lazyvalgrade.classfile._

/** Prints a unified diff between two text blocks.
  *
  * @param text1
  *   First text block
  * @param text2
  *   Second text block
  * @param methodName
  *   Name of the method being compared (for context)
  */
def printUnifiedDiff(text1: String, text2: String, methodName: String): Unit =
  val lines1 = text1.split("\n")
  val lines2 = text2.split("\n")

  // Simple LCS-based diff (not optimal but good enough for our purposes)
  case class DiffLine(line: String, lineType: String) // lineType: "context", "remove", "add"

  val result = scala.collection.mutable.ArrayBuffer[DiffLine]()
  var i = 0
  var j = 0

  while i < lines1.length || j < lines2.length do
    if i < lines1.length && j < lines2.length && lines1(i) == lines2(j) then
      result += DiffLine(lines1(i), "context")
      i += 1
      j += 1
    else
      // Find next matching line
      var foundMatch = false
      var lookAhead = 1
      while !foundMatch && lookAhead <= 5 do
        // Check if line1[i] matches line2[j+lookAhead]
        if i < lines1.length && j + lookAhead < lines2.length && lines1(i) == lines2(j + lookAhead) then
          // Lines j..j+lookAhead-1 were added
          (j until j + lookAhead).foreach: k =>
            result += DiffLine(lines2(k), "add")
          j += lookAhead
          foundMatch = true
        // Check if line1[i+lookAhead] matches line2[j]
        else if i + lookAhead < lines1.length && j < lines2.length && lines1(i + lookAhead) == lines2(j) then
          // Lines i..i+lookAhead-1 were removed
          (i until i + lookAhead).foreach: k =>
            result += DiffLine(lines1(k), "remove")
          i += lookAhead
          foundMatch = true
        else
          lookAhead += 1

      if !foundMatch then
        // Just show as changed
        if i < lines1.length then
          result += DiffLine(lines1(i), "remove")
          i += 1
        if j < lines2.length then
          result += DiffLine(lines2(j), "add")
          j += 1

  // Print with context (show 3 lines before/after changes)
  val contextLines = 3
  var lastChangeIdx = -1000

  result.zipWithIndex.foreach: (diffLine, idx) =>
    val isChange = diffLine.lineType != "context"
    val nearChange = result.indices.exists: i =>
      math.abs(i - idx) <= contextLines && result(i).lineType != "context"

    if isChange || nearChange then
      val prefix = diffLine.lineType match
        case "context" => "     "
        case "remove" => "-    "
        case "add" => "+    "

      println(s"$prefix${diffLine.line}")
    else if idx == lastChangeIdx + 1 && idx < result.length - 1 then
      println("      ...")

    if nearChange then
      lastChangeIdx = idx

/** Compares two ClassInfo instances and prints detailed differences.
  *
  * @param class1
  *   First class to compare
  * @param class2
  *   Second class to compare
  * @param label1
  *   Label for first class (e.g., file path)
  * @param label2
  *   Label for second class (e.g., file path)
  * @param bytes1
  *   Raw bytes of first class (for full dump comparison)
  * @param bytes2
  *   Raw bytes of second class (for full dump comparison)
  */
def compareClasses(
    class1: ClassInfo,
    class2: ClassInfo,
    label1: String,
    label2: String,
    bytes1: Array[Byte],
    bytes2: Array[Byte]
): Unit =
  println(s"\n${"=" * 80}")
  println(s"Comparing: $label1 vs $label2")
  println(s"${"=" * 80}\n")

  // First check if the full dumps are identical
  val parser = ClassfileParser()
  val dump1Result = parser.parseClassfile(bytes1).map(_ => getFullDump(bytes1))
  val dump2Result = parser.parseClassfile(bytes2).map(_ => getFullDump(bytes2))

  (dump1Result, dump2Result) match
    case (Right(dump1), Right(dump2)) =>
      if dump1 == dump2 then
        println("✅ FULLY IDENTICAL - Complete bytecode dump matches perfectly")
        return
      else
        println("⚠️  Full bytecode dumps differ - analyzing differences...")

        // Show line-by-line diff of the dumps
        val lines1 = dump1.split("\n")
        val lines2 = dump2.split("\n")
        val maxLines = math.max(lines1.length, lines2.length)

        println("\n📋 Line-by-line comparison:")
        var diffCount = 0
        (0 until maxLines).foreach: i =>
          val line1 = if i < lines1.length then lines1(i) else ""
          val line2 = if i < lines2.length then lines2(i) else ""

          if line1 != line2 then
            diffCount += 1
            if diffCount <= 20 then // Show first 20 differences
              println(f"\n  Line $i%3d:")
              println(s"    - [$label1] $line1")
              println(s"    + [$label2] $line2")

        if diffCount > 20 then
          println(s"\n  ... and ${diffCount - 20} more differences")
        else if diffCount > 0 then
          println(s"\n  Total: $diffCount lines differ")

        println("\n" + "─" * 80)
        println("Detailed analysis:")
        println()

    case (Left(error), _) =>
      println(s"❌ Error parsing first class: ${error.message}")
      return
    case (_, Left(error)) =>
      println(s"❌ Error parsing second class: ${error.message}")
      return

  // Compare fields
  val fields1 = class1.fields.map(f => (f.name, f)).toMap
  val fields2 = class2.fields.map(f => (f.name, f)).toMap

  val allFields = (fields1.keySet ++ fields2.keySet).toSeq.sorted

  if allFields.nonEmpty then
    println("FIELDS:")
    allFields.foreach: fieldName =>
      (fields1.get(fieldName), fields2.get(fieldName)) match
        case (Some(f1), Some(f2)) if f1 == f2 =>
          println(s"  ✓ $fieldName: identical")
        case (Some(f1), Some(f2)) =>
          println(s"  ⚠️  $fieldName: differs")
          println(s"      $label1: ${OpcodeUtils.accessFlagsToString(f1.access)} ${f1.descriptor}")
          println(s"      $label2: ${OpcodeUtils.accessFlagsToString(f2.access)} ${f2.descriptor}")
        case (Some(f1), None) =>
          println(s"  - $fieldName: only in $label1")
        case (None, Some(f2)) =>
          println(s"  + $fieldName: only in $label2")
        case _ => ()

  // Compare methods
  val methods1 = class1.methods.map(m => (m.name + m.descriptor, m)).toMap
  val methods2 = class2.methods.map(m => (m.name + m.descriptor, m)).toMap

  val allMethods = (methods1.keySet ++ methods2.keySet).toSeq.sorted

  if allMethods.nonEmpty then
    println("\nMETHODS:")
    allMethods.foreach: methodKey =>
      (methods1.get(methodKey), methods2.get(methodKey)) match
        case (Some(m1), Some(m2)) =>
          val instrMatch = m1.instructions == m2.instructions
          val accessMatch = m1.access == m2.access
          val bytecodeMatch = m1.bytecodeText == m2.bytecodeText

          if instrMatch && accessMatch && bytecodeMatch then
            println(s"  ✓ ${m1.name}${m1.descriptor}: identical")
          else if instrMatch && accessMatch && !bytecodeMatch then
            println(s"  ⚠️  ${m1.name}${m1.descriptor}: instructions match but bytecode differs (metadata/frames)")
            println(s"\n      Git-style diff:")
            printUnifiedDiff(m1.bytecodeText, m2.bytecodeText, s"${m1.name}${m1.descriptor}")
            println()
          else
            println(s"  🔴 ${m1.name}${m1.descriptor}: differs")

            if !accessMatch then
              println(s"      Access flags differ:")
              println(s"        $label1: ${OpcodeUtils.accessFlagsToString(m1.access)}")
              println(s"        $label2: ${OpcodeUtils.accessFlagsToString(m2.access)}")

            if !instrMatch then
              println(s"      Instruction count: $label1=${m1.instructions.size}, $label2=${m2.instructions.size}")
              println(s"\n      Git-style diff:")
              printUnifiedDiff(m1.bytecodeText, m2.bytecodeText, s"${m1.name}${m1.descriptor}")
              println()

        case (Some(m1), None) =>
          println(s"  - ${m1.name}${m1.descriptor}: only in $label1")
        case (None, Some(m2)) =>
          println(s"  + ${m2.name}${m2.descriptor}: only in $label2")
        case _ => ()

/** Gets full bytecode dump for a class file.
  *
  * @param bytes
  *   Raw class file bytes
  * @return
  *   Full textual dump of the class
  */
def getFullDump(bytes: Array[Byte]): String =
  import org.objectweb.asm._
  import org.objectweb.asm.util._
  import java.io.{StringWriter, PrintWriter}

  val stringWriter = new StringWriter()
  val printWriter = new PrintWriter(stringWriter)
  val textifier = new Textifier()
  val traceClassVisitor = new TraceClassVisitor(null, textifier, printWriter)
  val classReader = new ClassReader(bytes)
  classReader.accept(traceClassVisitor, ClassReader.EXPAND_FRAMES)
  stringWriter.toString

/** Main entry point for the script.
  *
  * @param args
  *   Command line arguments: classfile1 classfile2
  */
def main(args: String*): Unit =
  if args.length != 2 then
    println("Usage: scala-cli run classfile-diff-v2.sc -- <classfile1> <classfile2>")
    println("Example: scala-cli run classfile-diff-v2.sc -- workspace/simple-lazy-val/3.3.4/.scala-build/3.3_*/classes/main/Main$.class workspace/simple-lazy-val/3.8.0/.scala-build/3.8_*/classes/main/Main$.class")
    sys.exit(1)

  val file1Path = os.Path(args(0), os.pwd)
  val file2Path = os.Path(args(1), os.pwd)

  if !os.exists(file1Path) then
    println(s"Error: File not found: $file1Path")
    sys.exit(1)

  if !os.exists(file2Path) then
    println(s"Error: File not found: $file2Path")
    sys.exit(1)

  println(s"Parsing: $file1Path")
  val bytes1 = os.read.bytes(file1Path)

  println(s"Parsing: $file2Path")
  val bytes2 = os.read.bytes(file2Path)

  val parser = ClassfileParser()

  val result1 = parser.parseClassfile(bytes1)
  val result2 = parser.parseClassfile(bytes2)

  (result1, result2) match
    case (Right(class1), Right(class2)) =>
      // First use comparator to check if identical
      val comparisonResult = ClassfileComparator.compare(class1, class2, Some(bytes1), Some(bytes2))
      comparisonResult match
        case ComparisonResult.FullyIdentical =>
          println(s"\n${"=" * 80}")
          println(s"Comparing: ${args(0)} vs ${args(1)}")
          println(s"${"=" * 80}\n")
          println("✅ FULLY IDENTICAL - Complete bytecode dump matches perfectly")
        case ComparisonResult.Different(diffs) =>
          println(s"\n${"=" * 80}")
          println(s"Comparing: ${args(0)} vs ${args(1)}")
          println(s"${"=" * 80}\n")
          println(s"⚠️  Found ${diffs.size} difference(s)\n")

          // Show detailed comparison
          compareClasses(class1, class2, args(0), args(1), bytes1, bytes2)
    case (Left(error), _) =>
      println(s"❌ Failed to parse ${args(0)}: ${error.message}")
      sys.exit(1)
    case (_, Left(error)) =>
      println(s"❌ Failed to parse ${args(1)}: ${error.message}")
      sys.exit(1)

main(args*)
