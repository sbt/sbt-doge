lazy val commonSettings = Seq(
  version in ThisBuild := "0.1.5",
  // git.baseVersion in ThisBuild := "0.1.6",
  organization in ThisBuild := "com.eed3si9n"
)

lazy val root = (project in file(".")).
  // enablePlugins(GitVersioning).
  settings(
    commonSettings,
    sbtPlugin := true,
    name := "sbt-doge",
    description := "sbt plugin to aggregate across crossScalaVerions for muilti-project builds",
    licenses := Seq("MIT License" -> url("http://opensource.org/licenses/MIT")),
    scalacOptions := Seq("-deprecation", "-unchecked")
  )
