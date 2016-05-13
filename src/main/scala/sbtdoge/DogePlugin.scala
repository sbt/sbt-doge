package sbtdoge

import sbt._, Keys._, syntax._
import Cross.{ requireSession }
import sbt.complete.{ DefaultParsers, Parser }
import sbt.internal.CommandStrings
import CommandStrings.{ SwitchCommand, switchHelp }
import sbt.internal.inc.ScalaInstance
import Def.{ ScopedKey, Setting }

object DogePlugin extends AutoPlugin {
  import Doge._

  override def trigger = allRequirements

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    commands ++= Seq(
      switchCommand("wow"),
      crossBuildCommand("much"),
      crossBuildCommand("so"),
      crossBuildCommand("very"),
      crossBuildCommand("such"),
      runWithCommand("plz")
    )
  )
}

/**
 * This overrides the built in cross commands.
 */
object CrossPerProjectPlugin extends AutoPlugin {
  override def trigger = noTrigger

  import Doge._

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    commands ~= { existing => Seq(
      overrideSwitchCommand,
      overrideCrossBuildCommand,
      runWithCommand("+++")
    ) ++ existing }
  )
}

object Doge {
  import DefaultParsers._

  def crossHelp(commandName: String): Help = Help.more(commandName, "aggregate across crossScalaVersions and subprojects")

  def crossParser(commandName: String)(state: State): Parser[String] =
    token(commandName <~ Space) flatMap { _ => token(matched(state.combinedParser)) }

  def aggregate(state: State): Seq[ProjectRef] =
    {
      val x = Project.extract(state)
      import x._
      currentProject.aggregate
    }
  def crossVersions(state: State, proj: ProjectRef): Seq[String] =
    {
      val x = Project.extract(state)
      import x._
      (crossScalaVersions in proj get structure.data) getOrElse {
        // reading scalaVersion is a one-time deal
        (scalaVersion in proj get structure.data).toSeq
      }
    }

  /**
   * Parse the given command into either an aggregate command or a command for a project
   */
  private def parseCommand(command: String): Either[String, (String, String)] = {
    import DefaultParsers._
    val parser = (OpOrID <~ charClass(_ == '/', "/")) ~ any.* map {
      case project ~ cmd => (project, cmd.mkString)
    }
    Parser.parse(command, parser).left.map(_ => command)
  }

  def switchBack(x: Extracted) = {
    scalaVersion in x.currentRef get x.structure.data map (SwitchCommand + " " + _) toList
  }

  def crossBuildCommand(commandName: String): Command =
    Command.arb(requireSession(crossParser(commandName)), crossHelp(commandName))(crossBuildCommandImpl)

  def overrideCrossBuildCommand: Command =
    Command.arb(requireSession(Cross.crossParser), CommandStrings.crossHelp)(crossBuildCommandImpl)

  def crossBuildCommandImpl(state: State, command: String): State = {
    val x = Project.extract(state)
    import x._

    val (aggs, aggCommand) = parseCommand(command) match {
      case Right((project, cmd)) =>
        (structure.allProjectRefs.filter(_.project == project), cmd)
      case Left(cmd) => (aggregate(state), cmd)
    }

    val switchBackCommand = switchBack(x)

    // if we support scalaVersion, projVersions should be cached somewhere since
    // running ++2.11.1 is at the root level is going to mess with the scalaVersion for the aggregated subproj
    val projVersions = (aggs flatMap { proj =>
      crossVersions(state, proj) map { (proj.project, _) }
    }).toList

    if (projVersions.isEmpty) {
      state
    } else {
      // Group all the projects by scala version
      projVersions.groupBy(_._2).mapValues(_.map(_._1)).toSeq.flatMap {
        case (version, Seq(project)) =>
          // If only one project for a version, issue it directly
          Seq(s"wow $version $project/$aggCommand")
        case (version, projects) if aggCommand.contains(" ") =>
          // If the command contains a space, then the all command won't work because it doesn't support issuing
          // commands with spaces, so revert to running the command on each project one at a time
          s"wow $version" :: projects.map(project => s"$project/$aggCommand")
        case (version, projects) =>
          // First switch scala version, then use the all command to run the command on each project concurrently
          Seq("wow " + version, projects.map(_ + "/" + aggCommand).mkString("all ", " ", ""))
      } ::: switchBackCommand ::: state
    }
  }

  // Better implementation of ++ operator
  def switchCommand(commandName: String): Command =
    Command.arb(requireSession(switchParser(commandName)), switchHelp)(switchCommandImpl)

  def overrideSwitchCommand: Command =
    Command.arb(requireSession(Cross.switchParser), switchHelp)(switchCommandImpl)

  def switchCommandImpl(state: State, args: (String, String)): State = {
    val (arg, command) = args
    val (fixedState, version) = updateState(state, arg)

    if (!command.isEmpty) command :: fixedState
    else fixedState
  }

  def runWithCommand(commandName: String): Command =
    Command.arb(requireSession(runWithParser(commandName)), runWithHelp(commandName))(runWithCommandImpl)

  def runWithCommandImpl(state: State, args: (String, String)): State = {
    val (arg, command) = args
    val (fixedState, version) = updateState(state, arg)

    val switchBackCommand = switchBack(Project.extract(state))

    parseCommand(command) match {
      case Right(_) =>
        // A project is specified, run as is
        command :: switchBackCommand ::: fixedState
      case Left(_) =>

        // No project specified, only run for the projects that are compatible
        val aggs = aggregate(state)

        val projVersions = aggs map { proj =>
          proj -> crossVersions(state, proj)
        }

        val binaryVersion = CrossVersion.binaryScalaVersion(version)

        projVersions.collect {
          case (project, versions)
            if versions.exists(v => CrossVersion.binaryScalaVersion(v) == binaryVersion) =>
            project.project + "/" + command
        } ::: switchBackCommand ::: fixedState
    }

  }

  private def updateState(state: State, arg: String): (State, String) = {
    val x = Project.extract(state)
    import x._
    val aggs = aggregate(state)

    val (resolveVersion, homePath) = arg.split("=") match {
      case Array(v, h) => (v, h)
      case _           => ("", arg)
    }
    val home = IO.resolve(x.currentProject.base, new File(homePath))
    val exludeCurrentAndAgg = excludeProjects((currentRef :: aggs.toList).toSet)

    // Basic Algorithm.
    // 1. First we figure out what the new scala instances should be, create settings for them.
    // 2. Find any non-overridden scalaVersion setting in the whole build and force it to delegate
    //    to the new global settings.
    // 3. Append these to the session, so that the session is up-to-date and
    //    things like set/session clear, etc. work.
    val (add, exclude, version) =
      if (home.exists) {
        val instance = ScalaInstance(home)(state.classLoaderCache.apply _)
        state.log.info("Setting Scala home to " + home + " with actual version " + instance.actualVersion)
        val version = if (resolveVersion.isEmpty) instance.actualVersion else resolveVersion
        state.log.info("\tand using " + version + " for resolving dependencies.")
        val settings = Seq(
          scalaVersion in GlobalScope := version,
          scalaHome in GlobalScope := Some(home),
          scalaInstance in GlobalScope := instance
        )
        (settings, { s: Setting[_] =>
          excludeKeys(Set(scalaVersion.key, scalaHome.key, scalaInstance.key))(s) &&
            exludeCurrentAndAgg(s)
        }, version)
      } else if (!resolveVersion.isEmpty) {
        sys.error("Scala home directory did not exist: " + home)
      } else {
        state.log.info("Setting version to " + arg)
        val settings = Seq(
          scalaVersion in GlobalScope := arg,
          scalaHome in GlobalScope := None
        )
        (settings, { s: Setting[_] =>
          excludeKeys(Set(scalaVersion.key, scalaHome.key))(s) &&
            exludeCurrentAndAgg(s)
        }, arg)
      }
    // TODO - Track delegates and avoid regenerating.
    val delegates: Seq[Setting[_]] = session.mergeSettings collect {
      case x if exclude(x) => delegateToGlobal(x.key)
    }
    val fixedSession = session.appendRaw(add ++ delegates)
    (BuiltinCommands.reapply(fixedSession, structure, state), version)
  }

  def switchParser(commandName: String)(state: State): Parser[(String, String)] =
    {
      import DefaultParsers._
      def versionAndCommand(spacePresent: Boolean) = {
        val x = Project.extract(state)
        import x._
        val knownVersions = crossVersions(state, currentRef)
        val version = token(StringBasic.examples(knownVersions: _*))
        val spacedVersion = if (spacePresent) version else version & spacedFirst(commandName)
        val optionalCommand = token(Space ~> matched(state.combinedParser)) ?? ""
        spacedVersion ~ optionalCommand
      }
      def spacedFirst(name: String) = opOrIDSpaced(name) ~ any.+

      token(commandName ~> OptSpace) flatMap { sp => versionAndCommand(sp.nonEmpty) }
    }

  private def runWithParser(commandName: String)(state: State): Parser[(String, String)] =
  {
    import DefaultParsers._
    def versionAndCommand(spacePresent: Boolean) = {
      val x = Project.extract(state)
      import x._
      val knownVersions = crossVersions(state, currentRef)
      val version = token(StringBasic.examples(knownVersions: _*))
      val spacedVersion = if (spacePresent) version else version & spacedFirst(commandName)
      val command = token(Space ~> matched(state.combinedParser))
      spacedVersion ~ command
    }
    def spacedFirst(name: String) = opOrIDSpaced(name) ~ any.+

    token(commandName ~> OptSpace) flatMap { sp => versionAndCommand(sp.nonEmpty) }
  }

  private def runWithHelp(commandName: String) = Help.more(commandName,
    s"""$commandName <scala-version> <command>
      |  Runs the command with the Scala version.
      |
      |  Sets the `scalaVersion` to <scalaVersion> and reloads the build, then runs the given
      |  command.  If the command is for a single project, just executes that project,
      |  otherwise it aggregates all the projects that are binary compatible with the given
      |  scala version and executes those.
      |
      |  After running the command, it leaves the scala version back how it was.
      |
      |$commandName [<scala-version>=]<scala-home> <command>
	    |  Uses the Scala installation at <scala-home> by configuring the scalaHome setting for
	    |  all projects.
      |
      |	 If <scala-version> is specified, it is used as the value of the scalaVersion setting.
      |  This is important when using managed dependencies.  This version will determine the
      |  cross-version used as well as transitive dependencies.
      |
    """.stripMargin)

  // Creates a delegate for a scoped key that pulls the setting from the global scope.
  private[this] def delegateToGlobal[T](key: ScopedKey[T]): Setting[_] =
    SettingKey[T](key.key) in key.scope := (SettingKey[T](key.key) in GlobalScope).value

  private[this] def excludeKeys(keys: Set[AttributeKey[_]]): Setting[_] => Boolean =
    _.key match {
      case ScopedKey(Scope(_, Global, Global, _), key) if keys.contains(key) => true
      case _ => false
    }

  private[this] def excludeProjects(projs: Set[Reference]): Setting[_] => Boolean =
    _.key match {
      case ScopedKey(Scope(Select(pr), _, _, _), _) if projs.contains(pr) => true
      case _ => false 
    }
}
