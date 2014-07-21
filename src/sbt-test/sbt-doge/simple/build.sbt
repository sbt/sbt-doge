def commonSettings: Seq[Def.Setting[_]] = Seq(
  organization := "com.example.doge",
  version := "0.1-SNAPSHOT",
  ivyPaths := new IvyPaths((baseDirectory in ThisBuild).value, Some((baseDirectory in ThisBuild).value / "ivy-cache"))
)

lazy val rootProj = (project in file(".")).
  aggregate(libProj, fooPlugin).
  settings(commonSettings: _*)

lazy val libProj = (project in file("lib")).
  settings(commonSettings: _*).
  settings(
    name := "foo-lib",
    scalaVersion := "2.11.1",
    crossScalaVersions := Seq("2.11.1", "2.10.4")
  )

lazy val fooPlugin =(project in file("sbt-foo")).
  settings(commonSettings: _*).
  settings(
    name := "sbt-foo",
    sbtPlugin := true,
    scalaVersion := "2.10.4"
  )
