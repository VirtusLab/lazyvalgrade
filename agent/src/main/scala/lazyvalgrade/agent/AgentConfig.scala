package lazyvalgrade.agent

/** Configuration for the LazyValGrade java agent.
  *
  * Parsed from the agent arguments string (comma-separated key=value pairs).
  *
  * @param verbose
  *   Log patched classes to stderr
  * @param includes
  *   Only transform classes matching these package prefixes (dot-separated)
  * @param excludes
  *   Skip classes matching these package prefixes (dot-separated)
  */
final case class AgentConfig(
    verbose: Boolean = false,
    debug: Boolean = false,
    includes: Seq[String] = Seq.empty,
    excludes: Seq[String] = Seq.empty
) {

  /** Check if a class (internal name with `/` separators) should be transformed. */
  def shouldTransform(internalName: String): Boolean = {
    val dotName = internalName.replace('/', '.')

    // If includes are specified, class must match at least one
    val included = includes.isEmpty || includes.exists(prefix => dotName.startsWith(prefix))
    // If excludes are specified, class must not match any
    val excluded = excludes.nonEmpty && excludes.exists(prefix => dotName.startsWith(prefix))

    included && !excluded
  }
}

object AgentConfig {

  /** Parse agent config from the agentArgs string.
    *
    * Format: comma-separated options, e.g.:
    *   "verbose,include=com.example.,exclude=com.example.internal."
    */
  def parse(agentArgs: String): AgentConfig = {
    if (agentArgs == null || agentArgs.trim.isEmpty) return AgentConfig()

    val parts = agentArgs.split(',').map(_.trim).filter(_.nonEmpty)
    var verbose = false
    var debug = false
    val includes = scala.collection.mutable.ArrayBuffer[String]()
    val excludes = scala.collection.mutable.ArrayBuffer[String]()

    parts.foreach { part =>
      if (part == "verbose") {
        verbose = true
      } else if (part == "debug") {
        debug = true
      } else if (part.startsWith("include=")) {
        includes += part.stripPrefix("include=")
      } else if (part.startsWith("exclude=")) {
        excludes += part.stripPrefix("exclude=")
      }
    }

    AgentConfig(verbose, debug, includes.toSeq, excludes.toSeq)
  }
}
