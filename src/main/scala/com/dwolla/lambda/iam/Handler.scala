package com.dwolla.lambda.iam

import cats.effect._
import cats.effect.syntax.all._
import feral.lambda.cloudformation.CloudFormationCustomResourceRequest
import feral.lambda.{INothing, IOLambda, LambdaEnv}
import io.circe.JsonObject
import org.typelevel.log4cats.slf4j.Slf4jLogger

class Handler extends IOLambda[CloudFormationCustomResourceRequest[JsonObject], INothing] {
  override def handler: Resource[IO, LambdaEnv[IO, CloudFormationCustomResourceRequest[JsonObject]] => IO[Option[INothing]]] = {
    Slf4jLogger
      .create[IO]
      .toResource
      .flatMap { implicit logger =>
        new IamUserCleanupLambda[IO](IamAlg.resource[IO]).handler
      }
  }
}
