// addSbtPlugin("me.lessis" % "bintray-sbt" % "0.2.1")
lazy val bintraySbt = RootProject(uri("git://github.com/eed3si9n/bintray-sbt#topic/sbt1.0.0-M4"))
lazy val root = (project in file(".")).
  dependsOn(bintraySbt)
