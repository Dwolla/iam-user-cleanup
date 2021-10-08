ThisBuild / organization := "com.dwolla"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/iam-user-cleanup"))
ThisBuild / description := "CloudFormation custom resource that removes manually-added IAM user attributes prior to the user being deleted by CloudFormation"
ThisBuild / licenses += ("MIT", url("http://opensource.org/licenses/MIT"))
ThisBuild / startYear := Option(2019)
ThisBuild / scalaVersion := "2.13.6"
ThisBuild / githubWorkflowJavaVersions := Seq("adopt@1.11")
ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("test"), name = Option("Run tests")),
  WorkflowStep.Sbt(List("universal:packageBin"), name = Option("Package artifact")),
)
ThisBuild / developers := List(
  Developer(
    "bpholt",
    "Brian Holt",
    "bholt+iam-user-cleanup@dwolla.com",
    url("https://dwolla.com")
  ),
)

lazy val `iam-user-cleanup` = (project in file("."))
  .settings(
    topLevelDirectory := None,
    maintainer := developers.value.head.email,
    libraryDependencies ++= {
      val circeVersion = "0.14.1"
      val fs2Version = "3.1.5"
      val awsJavaSdkVersion = "2.17.52"

      Seq(
        "com.dwolla" %% "scala-cloudformation-custom-resource" % "4.0.0-M3",
        "software.amazon.awssdk" % "iam" % awsJavaSdkVersion,
        "software.amazon.awssdk" % "ecs" % awsJavaSdkVersion,
        "co.fs2" %% "fs2-core" % fs2Version,
        "co.fs2" %% "fs2-reactive-streams" % fs2Version,
        "org.scala-lang.modules" %% "scala-collection-compat" % "2.5.0",
        "io.circe" %% "circe-core" % circeVersion,
        "io.circe" %% "circe-generic" % circeVersion,
        "org.scalatest" %% "scalatest" % "3.2.10" % Test,
        "com.dwolla" %% "testutils-scalatest-fs2" % "2.0.0-M6" % Test,
        "io.chrisdavenport" %% "cats-scalacheck" % "0.3.1" % Test,
        "io.circe" %% "circe-literal" % circeVersion % Test,
      )
    },
    publish := {},
    publishLocal := {},
    publishArtifact := false,
    Keys.`package` := file(""),
  )
  .enablePlugins(UniversalPlugin, JavaAppPackaging)

lazy val serverlessDeployCommand = settingKey[String]("serverless command to deploy the application")
serverlessDeployCommand := "serverless deploy --verbose"

lazy val deploy = taskKey[Int]("deploy to AWS")
deploy := Def.task {
  import scala.sys.process._

  val exitCode = Process(
    serverlessDeployCommand.value,
    Option((`iam-user-cleanup` / baseDirectory).value),
    "ARTIFACT_PATH" -> (`iam-user-cleanup` / Universal / packageBin).value.toString,
  ).!

  if (exitCode == 0) exitCode
  else throw new IllegalStateException("Serverless returned a non-zero exit code. Please check the logs for more information.")
}.value
