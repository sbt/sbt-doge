sbt-doge
========

sbt-doge is a sbt plugin to aggregate across `crossScalaVersions` for multi-project builds, which I call partial cross building.

![sbt-doge](sbt-doge.png?raw=true)

Current implementation of `+` cross building operator does not take in account for the `crossScalaVersions` of the sub projects. Until that's fixed, here's an anternative implementation of it.

setup
-----

This is an auto plugin, so you need sbt 0.13.5+. Put this in `project/doge.sbt`:

```scala
addSbtPlugin("com.eed3si9n" % "sbt-doge" % "0.1.1")
```

usage
-----

First, define a multi-project build with a root project aggregating some child projects:

```scala
def commonSettings: Seq[Def.Setting[_]] = Seq(
  organization := "com.example.doge",
  version := "0.1-SNAPSHOT"
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
  dependsOn(libProj).
  settings(commonSettings: _*).
  settings(
    name := "sbt-foo",
    sbtPlugin := true,
    scalaVersion := "2.10.4"
  )
```

Next run this from the root project:

```scala
> ;so clean; such test; very publishLocal
```

sbt-doge will break the above into the following commands and executes them:

```scala
> ++2.11.1
> libProj/clean
> ++2.10.4
> libProj/clean
> fooPlugin/clean
> ++2.10.4
> ++2.11.1
> libProj/test
> ++2.10.4
> libProj/test
> fooPlugin/test
> ++2.10.4
> ++2.11.1
> libProj/publishLocal
> ++2.10.4
> libProj/publishLocal
> fooPlugin/publishLocal
> ++2.10.4
```

It is looking into `aggregate` of the current project, and for each aggregated project, running a loop for each `crossScalaVersions` (or just `scalaVersion` if `crossScalaVersions` is not defined) and executing the passed in command. The currently supported prefixes are: `much`, `so`, `such`, and `very`.
