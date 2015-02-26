package sbtdoge

import sbt._
import Keys._
import Cross.{ requireSession }
import sbt.complete.{ DefaultParsers, Parser }
import CommandStrings.{ SwitchCommand, switchHelp }
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
      crossBuildCommand("such"))  
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
      overrideCrossBuildCommand
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

    val switchBackCommand = scalaVersion in currentRef get structure.data map (SwitchCommand + " " + _) toList

    // if we support scalaVersion, projVersions should be cached somewhere since
    // running ++2.11.1 is at the root level is going to mess with the scalaVersion for the aggregated subproj
    val projVersions = (aggs flatMap { proj =>
      crossVersions(state, proj) map { (proj.project, _) }
    }).toList

    if (projVersions.isEmpty) state
    else {
      val versions = (projVersions map { _._2 }).distinct
      versions flatMap { v =>
        val projects = (projVersions filter { _._2 == v } map { _._1 })
        ("wow" + " " + v) ::
          (projects map { _ + "/" + aggCommand })
      }
    } ::: switchBackCommand ::: state
  }

  // Better implementation of ++ operator
  def switchCommand(commandName: String): Command =
    Command.arb(requireSession(switchParser(commandName)), switchHelp)(switchCommandImpl)

  def overrideSwitchCommand: Command =
    Command.arb(requireSession(Cross.switchParser), switchHelp)(switchCommandImpl)

  def switchCommandImpl(state: State, args: (String, String)): State = {
    val (arg, command) = args
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
    val (add, exclude) =
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
        })
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
        })
      }
    // TODO - Track delegates and avoid regenerating.
    val delegates: Seq[Setting[_]] = session.mergeSettings collect {
      case x if exclude(x) => delegateToGlobal(x.key)
    }
    val fixedSession = session.appendRaw(add ++ delegates)
    val fixedState = BuiltinCommands.reapply(fixedSession, structure, state)
    if (!command.isEmpty) command :: fixedState
    else fixedState

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
