ThisBuild / organization := "com.dwolla"
ThisBuild / homepage := Some(url("https://github.com/Dwolla/iam-user-cleanup"))
ThisBuild / description := "CloudFormation custom resource that removes manually-added IAM user attributes prior to the user being deleted by CloudFormation"
ThisBuild / licenses += ("MIT", url("http://opensource.org/licenses/MIT"))
ThisBuild / startYear := Option(2019)
ThisBuild / scalaVersion := "2.13.9"
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("11"))
ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("test"), name = Option("Run tests")),
  WorkflowStep.Sbt(List("universal:packageBin"), name = Option("Package artifact")),
)
ThisBuild / githubWorkflowPublishTargetBranches := Seq.empty
ThisBuild / githubWorkflowPublish := Seq.empty
ThisBuild / developers := List(
  Developer(
    "bpholt",
    "Brian Holt",
    "bholt+iam-user-cleanup@dwolla.com",
    url("https://dwolla.com")
  ),
)
ThisBuild / libraryDependencies ++= Seq(
  compilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
  compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
)

lazy val `iam-user-cleanup` = (project in file("."))
  .settings(
    topLevelDirectory := None,
    maintainer := developers.value.head.email,
    libraryDependencies ++= {
      val natchezVersion = "0.1.6"
      val feralVersion = "0.1.0-M9"
      val circeVersion = "0.14.2"
      val awsJavaSdkVersion = "2.17.129"
      val scalacheckEffectVersion = "1.0.4"
      val log4catsVersion = "2.3.1"
      val http4sVersion = "0.23.12"

      Seq(
        "org.typelevel" %% "feral-lambda-cloudformation-custom-resource" % feralVersion,
        "org.tpolecat" %% "natchez-xray" % natchezVersion,
        "org.tpolecat" %% "natchez-http4s" % "0.3.2",
        "com.dwolla" %% "fs2-aws-java-sdk2" % "3.0.0-RC1",
        "software.amazon.awssdk" % "iam" % awsJavaSdkVersion,
        "io.circe" %% "circe-core" % circeVersion,
        "io.circe" %% "circe-generic" % circeVersion,
        "org.http4s" %% "http4s-ember-client" % http4sVersion,
        "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
        "org.typelevel" %% "cats-effect" % "3.3.12",
        "com.amazonaws" % "aws-lambda-java-log4j2" % "1.5.1" % Runtime,
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.17.2" % Runtime,
        "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test,
        "org.typelevel" %% "scalacheck-effect" % scalacheckEffectVersion % Test,
        "org.typelevel" %% "scalacheck-effect-munit" % scalacheckEffectVersion % Test,
        "org.typelevel" %% "log4cats-noop" % log4catsVersion % Test,
        "io.chrisdavenport" %% "cats-scalacheck" % "0.3.1" % Test,
        "io.circe" %% "circe-literal" % circeVersion % Test,
        "io.circe" %% "circe-testing" % circeVersion % Test,
        "com.eed3si9n.expecty" %% "expecty" % "0.15.4" % Test,
      )
    },
  )
  .enablePlugins(
    UniversalPlugin,
    JavaAppPackaging,
    ServerlessDeployPlugin,
  )
