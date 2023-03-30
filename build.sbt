ThisBuild / scalaVersion     := "2.13.10"
ThisBuild / version          := "1.0.0"
ThisBuild / organization     := "br.com.inbot"
ThisBuild / organizationName := "zprocess-resource"

lazy val root = (project in file("."))
  .settings(
    name := "zprocess-resource",
    libraryDependencies ++= Seq(
        "dev.zio" %% "zio" % "2.0.10",
        "dev.zio" %% "zio-test" % "2.0.10" % Test,
        "dev.zio" %% "zio-streams" % "2.0.10",
        "dev.zio" %% "zio-test-sbt" % "2.0.10" % Test,
        "dev.zio" %% "zio-test-magnolia" % "2.0.10" % Test,
        compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
