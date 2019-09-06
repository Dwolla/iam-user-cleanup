lazy val buildSettings = Seq(
  organization := "com.dwolla",
  homepage := Some(url("https://github.com/Dwolla/iam-user-cleanup")),
  description := "CloudFormation custom resource that removes manually-added IAM user attributes prior to the user being deleted by CloudFormation",
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  startYear := Option(2019),
  libraryDependencies ++= {
    val circeVersion = "0.12.0-RC4"
    val fs2Version = "1.1.0-M1"
    val awsJavaSdkVersion = "2.7.5"

    Seq(
      "com.dwolla" %% "scala-cloudformation-custom-resource" % "4.0.0-M1",
      "software.amazon.awssdk" % "iam" % awsJavaSdkVersion,
      "software.amazon.awssdk" % "ecs" % awsJavaSdkVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-reactive-streams" % fs2Version,
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.1",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "org.scalatest" %% "scalatest" % "3.0.8" % Test,
      "com.dwolla" %% "testutils-scalatest-fs2" % "2.0.0-M2" % Test,
      "com.ironcorelabs" %% "cats-scalatest" % "2.4.0" % Test,
      "io.circe" %% "circe-literal" % circeVersion % Test,
    )
  },
)

lazy val `iam-user-cleanup` = (project in file("."))
  .settings(buildSettings ++ noPublishSettings ++ assemblySettings: _*)

lazy val noPublishSettings = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false,
  Keys.`package` := file(""),
)

lazy val documentationSettings = Seq(
  autoAPIMappings := true,
  apiMappings ++= {
    // Lookup the path to jar (it's probably somewhere under ~/.ivy/cache) from computed classpath
    val classpath = (fullClasspath in Compile).value
    def findJar(name: String): File = {
      val regex = ("/" + name + "[^/]*.jar$").r
      classpath.find { jar => regex.findFirstIn(jar.data.toString).nonEmpty }.get.data // fail hard if not found
    }

    // Define external documentation paths
    Map(
      findJar("circe-generic-extra") -> url("http://circe.github.io/circe/api/io/circe/index.html"),
    )
  }
)

lazy val assemblySettings = Seq(
  assemblyMergeStrategy in assembly := {
    case PathList(ps @ _*) if ps.head == "codegen-resources" =>
      MergeStrategy.discard
    case PathList(ps @ _*) if ps.last == "io.netty.versions.properties" =>
      MergeStrategy.concat
    case PathList(ps @ _*) if ps.last == "Log4j2Plugins.dat" =>
      sbtassembly.Log4j2MergeStrategy.plugincache
    case x =>
      val oldStrategy = (assemblyMergeStrategy in assembly).value
      oldStrategy(x)
  },
  assemblyJarName in assembly := normalizedName.value + ".jar",
  publishArtifact in (Compile, packageBin) := false,
  publishArtifact in (Compile, packageSrc) := false,
  publishArtifact in (Compile, packageDoc) := false,
)
