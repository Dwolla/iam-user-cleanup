name := "iam-user-cleanup"

version := "0.1"

libraryDependencies ++= Seq(
  "com.dwolla" %% "scala-cloudformation-custom-resource" % "4.0.0-SNAPSHOT",
  "software.amazon.awssdk" % "iam" % "2.7.5",
  "co.fs2" %% "fs2-core" % "1.1.0-M1",
)
