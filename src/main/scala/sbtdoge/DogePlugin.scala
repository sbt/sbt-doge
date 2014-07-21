package sbtdoge

import sbt._
import Keys._
import Cross.{ requireSession }
import complete.{ DefaultParsers, Parser }
import CommandStrings.{ SwitchCommand, switchHelp }

object DogePlugin extends AutoPlugin {
  import DefaultParsers._
  import Doge._

  override def trigger = allRequirements

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    commands ++= Seq(
      crossBuildCommand("much"),
      crossBuildCommand("so"),
      crossBuildCommand("very"),
      crossBuildCommand("such"))  
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
        (scalaVersion in proj get structure.data).toSeq
      }
    }

  def crossBuildCommand(commandName: String): Command =
    Command.arb(requireSession(crossParser(commandName)), crossHelp(commandName)) { (state, command) =>
      val x = Project.extract(state)
      import x._
      val aggs = aggregate(state)
      val switchBackCommand = scalaVersion in currentRef get structure.data map (SwitchCommand + " " + _) toList
      val projVersions = (aggs flatMap { proj =>
        crossVersions(state, proj) map { (proj.project, _) }
      }).toList
      if (projVersions.isEmpty) state
      else ({
        val versions = (projVersions map { _._2 }).distinct
        versions flatMap { v =>
          val projects = (projVersions filter { _._2 == v } map { _._1 })
          (SwitchCommand + " " + v) ::
          (projects map { _ + "/" + command })
        }
      } ::: switchBackCommand ::: state)
    }
}
