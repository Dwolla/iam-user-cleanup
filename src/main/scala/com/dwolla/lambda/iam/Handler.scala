package com.dwolla.lambda.iam

import cats.effect._
import com.dwolla.lambda.cloudformation._

class Handler() extends IOCustomResourceHandler {
  lazy val iamResource: Resource[IO, IamAlg[IO]] = IamAlg.resource[IO]

  private val proxied =
    new IamUserCleanupLambda(iamResource)

  override def handleRequest(req: CloudFormationCustomResourceRequest): IO[HandlerResponse] =
    proxied.handleRequest(req)
}
