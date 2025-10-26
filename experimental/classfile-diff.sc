//> using scala 3.7.3
//> using dep com.lihaoyi::os-lib:0.11.3
//> using dep org.ow2.asm:asm:9.7
//> using dep org.ow2.asm:asm-util:9.7
//> using dep org.ow2.asm:asm-tree:9.7

import org.objectweb.asm._
import org.objectweb.asm.tree._
import org.objectweb.asm.util._
import scala.jdk.CollectionConverters._
import java.io.{StringWriter, PrintWriter}

case class FieldInfo(
    name: String,
    descriptor: String,
    access: Int,
    signature: Option[String]
)

case class InstructionInfo(
    opcode: Int,
    opcodeString: String,
    details: String
)

case class MethodInfo(
    name: String,
    descriptor: String,
    access: Int,
    signature: Option[String],
    instructions: Seq[InstructionInfo],
    localVariables: Seq[String],
    exceptions: Seq[String],
    bytecodeText: String
)

case class ClassInfo(
    name: String,
    superName: String,
    interfaces: Seq[String],
    access: Int,
    fields: Seq[FieldInfo],
    methods: Seq[MethodInfo]
)

def getMethodBytecodeText(fullDump: String, methodName: String, methodDesc: String): String =
  // Extract just the method we care about from the full dump
  val lines = fullDump.split("\n")

  // Find method signature line - it's the line with access flags followed by method name and descriptor
  val methodSignature = s"$methodName$methodDesc"
  val methodStart = lines.indexWhere(line =>
    line.trim.endsWith(methodSignature) || line.contains(s" $methodSignature")
  )

  if methodStart >= 0 then
    // Find the end - next method starts or we hit a field/class-level construct
    val methodEnd = lines.indexWhere(
      line => {
        val trimmed = line.trim
        val startsNewSection = line.startsWith("  // access flags") && 
          !line.startsWith("    ") && methodStart < lines.indexOf(line) - 1
          
        startsNewSection || (trimmed.startsWith("// access flags 0x") && !line.startsWith("    "))
      },
      methodStart + 1
    )

    val endIdx = if methodEnd > methodStart then methodEnd else lines.length
    lines.slice(methodStart, endIdx).mkString("\n")
  else
    s"Method $methodName$methodDesc not found"

def parseClassFile(bytes: Array[Byte]): ClassInfo =
  val classNode = new ClassNode()
  val classReader = new ClassReader(bytes)
  classReader.accept(classNode, ClassReader.EXPAND_FRAMES)

  // Generate full dump once for method extraction
  val fullDump = getFullClassDump(bytes)

  val fields = classNode.fields.asScala.map: field =>
    FieldInfo(
      name = field.name,
      descriptor = field.desc,
      access = field.access,
      signature = Option(field.signature)
    )

  val methods = classNode.methods.asScala.map: method =>
    val instructions = Option(method.instructions).map: insnList =>
      val insns = insnList.iterator().asScala.toSeq
      insns.map: insn =>
        val opcodeStr = if insn.getOpcode >= 0 then
          val printer = new Textifier()
          val methodVisitor = new TraceMethodVisitor(printer)
          insn.accept(methodVisitor)
          printer.getText.asScala.headOption
            .map(_.toString.trim)
            .getOrElse("???")
        else "LABEL/FRAME/LINE"

        InstructionInfo(
          opcode = insn.getOpcode,
          opcodeString = if insn.getOpcode >= 0 then opcodeToString(insn.getOpcode) else "META",
          details = opcodeStr
        )
    .getOrElse(Seq.empty).toSeq

    val localVars = Option(method.localVariables).map: lvs =>
      lvs.asScala.map: lv =>
        s"${lv.name}: ${lv.desc}"
      .toSeq
    .getOrElse(Seq.empty)

    val exceptions = Option(method.exceptions).map: exs =>
      exs.asScala.map(_.toString).toSeq
    .getOrElse(Seq.empty)

    val bytecodeText = getMethodBytecodeText(fullDump, method.name, method.desc)

    MethodInfo(
      name = method.name,
      descriptor = method.desc,
      access = method.access,
      signature = Option(method.signature),
      instructions = instructions,
      localVariables = localVars,
      exceptions = exceptions,
      bytecodeText = bytecodeText
    )

  ClassInfo(
    name = classNode.name,
    superName = classNode.superName,
    interfaces = classNode.interfaces.asScala.toSeq.map(_.toString),
    access = classNode.access,
    fields = fields.toSeq,
    methods = methods.toSeq
  )

def opcodeToString(opcode: Int): String =
  opcode match
    case Opcodes.ALOAD => "ALOAD"
    case Opcodes.ASTORE => "ASTORE"
    case Opcodes.GETFIELD => "GETFIELD"
    case Opcodes.PUTFIELD => "PUTFIELD"
    case Opcodes.GETSTATIC => "GETSTATIC"
    case Opcodes.PUTSTATIC => "PUTSTATIC"
    case Opcodes.INVOKEVIRTUAL => "INVOKEVIRTUAL"
    case Opcodes.INVOKESPECIAL => "INVOKESPECIAL"
    case Opcodes.INVOKESTATIC => "INVOKESTATIC"
    case Opcodes.INVOKEINTERFACE => "INVOKEINTERFACE"
    case Opcodes.RETURN => "RETURN"
    case Opcodes.ARETURN => "ARETURN"
    case Opcodes.IRETURN => "IRETURN"
    case Opcodes.LRETURN => "LRETURN"
    case Opcodes.MONITORENTER => "MONITORENTER"
    case Opcodes.MONITOREXIT => "MONITOREXIT"
    case Opcodes.NEW => "NEW"
    case Opcodes.DUP => "DUP"
    case Opcodes.ICONST_0 => "ICONST_0"
    case Opcodes.ICONST_1 => "ICONST_1"
    case Opcodes.BIPUSH => "BIPUSH"
    case Opcodes.SIPUSH => "SIPUSH"
    case Opcodes.LDC => "LDC"
    case Opcodes.IF_ICMPNE => "IF_ICMPNE"
    case Opcodes.IFEQ => "IFEQ"
    case Opcodes.IFNE => "IFNE"
    case Opcodes.GOTO => "GOTO"
    case _ => s"OPCODE_$opcode"

def accessFlagsToString(access: Int): String =
  val flags = Seq(
    (Opcodes.ACC_PUBLIC, "public"),
    (Opcodes.ACC_PRIVATE, "private"),
    (Opcodes.ACC_PROTECTED, "protected"),
    (Opcodes.ACC_STATIC, "static"),
    (Opcodes.ACC_FINAL, "final"),
    (Opcodes.ACC_SYNCHRONIZED, "synchronized"),
    (Opcodes.ACC_VOLATILE, "volatile"),
    (Opcodes.ACC_TRANSIENT, "transient"),
    (Opcodes.ACC_ABSTRACT, "abstract")
  ).filter((flag, _) => (access & flag) != 0)
    .map(_._2)

  if flags.isEmpty then "" else flags.mkString(" ")

def printClassInfo(info: ClassInfo, indent: String = ""): Unit =
  println(s"${indent}Class: ${info.name}")
  println(s"${indent}  Extends: ${info.superName}")
  if info.interfaces.nonEmpty then
    println(s"${indent}  Implements: ${info.interfaces.mkString(", ")}")
  println(s"${indent}  Access: ${accessFlagsToString(info.access)}")

  if info.fields.nonEmpty then
    println(s"${indent}  Fields:")
    info.fields.foreach: field =>
      val access = accessFlagsToString(field.access)
      println(s"${indent}    $access ${field.name}: ${field.descriptor}")

  if info.methods.nonEmpty then
    println(s"${indent}  Methods:")
    info.methods.foreach: method =>
      val access = accessFlagsToString(method.access)
      println(s"${indent}    $access ${method.name}${method.descriptor}")

      if method.instructions.nonEmpty then
        println(s"${indent}      Instructions (${method.instructions.size}):")
        method.instructions.zipWithIndex.foreach: (insn, idx) =>
          if insn.opcode >= 0 then
            println(f"${indent}        $idx%3d: ${insn.details}")

def getFullClassDump(bytes: Array[Byte]): String =
  val stringWriter = new StringWriter()
  val printWriter = new PrintWriter(stringWriter)
  val textifier = new Textifier()
  val traceClassVisitor = new TraceClassVisitor(null, textifier, printWriter)
  val classReader = new ClassReader(bytes)
  classReader.accept(traceClassVisitor, ClassReader.EXPAND_FRAMES)
  stringWriter.toString

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

def compareClasses(class1: ClassInfo, class2: ClassInfo, label1: String, label2: String, bytes1: Array[Byte], bytes2: Array[Byte]): Unit =
  println(s"\n${"=" * 80}")
  println(s"Comparing: $label1 vs $label2")
  println(s"${"=" * 80}\n")

  // First check if the full dumps are identical
  val dump1 = getFullClassDump(bytes1)
  val dump2 = getFullClassDump(bytes2)

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
          println(s"      $label1: ${accessFlagsToString(f1.access)} ${f1.descriptor}")
          println(s"      $label2: ${accessFlagsToString(f2.access)} ${f2.descriptor}")
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
              println(s"        $label1: ${accessFlagsToString(m1.access)}")
              println(s"        $label2: ${accessFlagsToString(m2.access)}")

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

def main(args: String*): Unit =
  if args.length != 2 then
    println("Usage: scala-cli run classfile-diff.sc -- <classfile1> <classfile2>")
    println("Example: scala-cli run classfile-diff.sc -- workspace/simple-lazy-val/3.3.4/.scala-build/3.3_*/classes/main/Main$.class workspace/simple-lazy-val/3.8.0/.scala-build/3.8_*/classes/main/Main$.class")
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
  val class1 = parseClassFile(bytes1)

  println(s"Parsing: $file2Path")
  val bytes2 = os.read.bytes(file2Path)
  val class2 = parseClassFile(bytes2)

  compareClasses(class1, class2, args(0), args(1), bytes1, bytes2)

main(args*)
