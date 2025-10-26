package lazyvalgrade.lazyval

import lazyvalgrade.classfile.OpcodeUtils

/** Formats lazy val detection and comparison results for display. */
object LazyValFormatter:

  /** Formats a detection result as a human-readable string. */
  def formatDetectionResult(result: LazyValDetectionResult): String =
    result match
      case LazyValDetectionResult.NoLazyVals =>
        "No lazy vals detected"

      case LazyValDetectionResult.LazyValsFound(lazyVals, version) =>
        val sb = new StringBuilder()
        sb.append(s"Detected ${lazyVals.size} lazy val(s) using $version\n")
        sb.append("=" * 60).append("\n")
        lazyVals.foreach { lv =>
          sb.append(formatLazyValInfo(lv)).append("\n")
        }
        sb.toString()

      case LazyValDetectionResult.MixedVersions(lazyVals) =>
        val sb = new StringBuilder()
        sb.append(s"WARNING: Mixed versions detected (${lazyVals.size} lazy vals)\n")
        sb.append("=" * 60).append("\n")
        lazyVals.groupBy(_.version).foreach { case (version, lvs) =>
          sb.append(s"\n$version (${lvs.size} lazy vals):\n")
          lvs.foreach { lv =>
            sb.append(s"  - ${lv.name} (index ${lv.index})\n")
          }
        }
        sb.toString()

  /** Formats a single LazyValInfo. */
  def formatLazyValInfo(lv: LazyValInfo): String =
    val sb = new StringBuilder()
    sb.append(s"Lazy Val: ${lv.name} (index ${lv.index})\n")
    sb.append(s"  Version: ${lv.version}\n")
    sb.append(s"  Storage field: ${lv.storageField.name} : ${lv.storageField.descriptor}")
    sb.append(s" [${OpcodeUtils.accessFlagsToString(lv.storageField.access)}]\n")

    lv.offsetField.foreach { field =>
      sb.append(s"  Offset field: ${field.name} : ${field.descriptor}")
      sb.append(s" [${OpcodeUtils.accessFlagsToString(field.access)}]\n")
    }

    lv.bitmapField.foreach { field =>
      sb.append(s"  Bitmap field: ${field.name} : ${field.descriptor}")
      sb.append(s" [${OpcodeUtils.accessFlagsToString(field.access)}]\n")
    }

    lv.varHandleField.foreach { field =>
      sb.append(s"  VarHandle field: ${field.name} : ${field.descriptor}")
      sb.append(s" [${OpcodeUtils.accessFlagsToString(field.access)}]\n")
    }

    lv.initMethod.foreach { method =>
      sb.append(s"  Init method: ${method.name}${method.descriptor}")
      sb.append(s" (${method.instructions.size} instructions)\n")
    }

    lv.accessorMethod.foreach { method =>
      sb.append(s"  Accessor: ${method.name}${method.descriptor}")
      sb.append(s" (${method.instructions.size} instructions)\n")
    }

    sb.toString()

  /** Formats a comparison result. */
  def formatComparisonResult(result: LazyValComparisonResult): String =
    result match
      case LazyValComparisonResult.BothNoLazyVals =>
        "Both classes have no lazy vals"

      case LazyValComparisonResult.OnlyOneHasLazyVals(firstHas, lazyVals) =>
        val which = if firstHas then "first" else "second"
        s"Only $which class has lazy vals (${lazyVals.size} total)"

      case LazyValComparisonResult.SameImplementation(version, lazyVals1, lazyVals2) =>
        s"Both classes use $version (${lazyVals1.size} and ${lazyVals2.size} lazy vals)"

      case LazyValComparisonResult.DifferentImplementations(v1, v2, lvs1, lvs2, diffs) =>
        val sb = new StringBuilder()
        sb.append(s"Different implementations detected:\n")
        sb.append(s"  First class:  $v1 (${lvs1.size} lazy vals)\n")
        sb.append(s"  Second class: $v2 (${lvs2.size} lazy vals)\n")
        sb.append(s"\nDifferences (${diffs.size} total):\n")
        diffs.foreach { diff =>
          sb.append(s"  - ${diff.description}\n")
        }
        sb.toString()

  /** Formats a summary suitable for logging. */
  def formatSummary(result: LazyValDetectionResult): String =
    result match
      case LazyValDetectionResult.NoLazyVals =>
        "No lazy vals"

      case LazyValDetectionResult.LazyValsFound(lazyVals, version) =>
        val names = lazyVals.map(_.name).mkString(", ")
        s"$version: $names"

      case LazyValDetectionResult.MixedVersions(lazyVals) =>
        val versions = lazyVals.map(_.version).distinct.mkString(", ")
        s"Mixed versions: $versions"

  /** Formats comparison summary. */
  def formatComparisonSummary(result: LazyValComparisonResult): String =
    result match
      case LazyValComparisonResult.BothNoLazyVals =>
        "No lazy vals in either class"

      case LazyValComparisonResult.OnlyOneHasLazyVals(firstHas, lazyVals) =>
        val which = if firstHas then "first" else "second"
        s"Only $which has lazy vals (${lazyVals.size})"

      case LazyValComparisonResult.SameImplementation(version, _, _) =>
        s"Same implementation: $version"

      case LazyValComparisonResult.DifferentImplementations(v1, v2, _, _, diffs) =>
        s"Different: $v1 vs $v2 (${diffs.size} differences)"
