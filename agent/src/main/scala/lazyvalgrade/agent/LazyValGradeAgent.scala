package lazyvalgrade.agent

import java.lang.instrument.Instrumentation

/** Java agent entry point for LazyValGrade.
  *
  * Patches Scala 3.0-3.7 lazy val bytecode at class-load time to use
  * VarHandle-based implementation (3.8+ format), avoiding sun.misc.Unsafe.
  *
  * Usage: java -javaagent:lazyvalgrade-agent.jar[=options] -jar app.jar
  *
  * Options (comma-separated):
  *   - verbose: Log patched classes to stderr
  *   - debug: Enable detailed file logging to /tmp/lazyvalgrade-agent-<pid>.log
  *   - include=com.example.: Only transform matching packages
  *   - exclude=com.example.internal.: Skip matching packages
  */
object LazyValGradeAgent {

  def premain(agentArgs: String, inst: Instrumentation): Unit = {
    val config = AgentConfig.parse(agentArgs)

    if (config.debug) {
      // Debug mode: log everything to file
      import scribe._
      import scribe.file._

      Logger.root
        .clearHandlers()
        .clearModifiers()
        .withHandler(
          writer = FileWriter(PathBuilder.static(java.nio.file.Paths.get(s"/tmp/lazyvalgrade-agent-${ProcessHandle.current().pid()}.log"))),
          minimumLevel = Some(Level.Trace)
        )
        .replace()
    } else {
      // Normal mode: suppress all scribe logging to avoid polluting host app stdout
      scribe.Logger.root
        .clearHandlers()
        .clearModifiers()
        .withHandler(minimumLevel = Some(scribe.Level.Error))
        .replace()
    }

    val transformer = new LazyValGradeTransformer(config)
    inst.addTransformer(transformer)

    if (config.verbose) {
      System.err.println("[lazyvalgrade] Agent installed")
    }
  }
}
