sbt-doge
========

sbt-doge is a sbt plugin to aggregate across `crossScalaVersions` and aggregate projects, which I call partial cross building.

Current implementation of `+` cross building operator does not take in account for the `crossScalaVersions` of the sub projects. Until that's fixed, here's an anternative implementation of it.

setup
-----

This is an auto plugin, so you need sbt 0.13.5+. Put this in `project/doge.sbt`:

```scala
addSbtPlugin("com.eed3si9n" % "sbt-doge" % "0.1.0")
```

usage
-----

```scala
> ;so clean; such test; very publishLocal
```

This will look into `aggregate` of the current project, and for each aggregated project, runs a loop for each `crossScalaVersions` and executes the passed in command. The currently supported prefixes are: `much`, `so`, `such`, and `very`.
