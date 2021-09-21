package com.dwolla.lambda.iam

import cats.effect._
import cats.effect.concurrent.Deferred
import com.dwolla.lambda.cloudformation._
import com.dwolla.lambda.iam.model._
import com.dwolla.testutils.IOSpec
import io.circe._
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers

class HandleRequestSpec extends IOSpec with Matchers {

  private val physicalResourceId = "physical-resource-id".asInstanceOf[PhysicalResourceId]
  private val username = "username"

  behavior of "HandleRequest"

  it should "return the physical resource ID on NoOps" inIO {
    val resource = Resource.pure[IO, IamAlg[IO]](new IamAlg[IO] {
      override def deleteMfaDevices(username: String): IO[Unit] =
        IO.raiseError(new NotImplementedError)

      override def deleteAccessKeys(username: String): IO[Unit] =
        IO.raiseError(new NotImplementedError)

    })

    for {
      res <- new IamUserCleanupLambda(resource).handleRequest(RequestParameters(NoOp, physicalResourceId, username))
    } yield {
      res should be(HandlerResponse(physicalResourceId, JsonObject("username"-> username.asJson)))
    }
  }

  it should "delete all the things" inIO {

    for {
      deferredDeleteMfaDevices <- Deferred[IO, String]
      deferredDeleteAccessKeys <- Deferred[IO, String]
      resource = Resource.pure[IO, IamAlg[IO]](new IamAlg[IO] {
        override def deleteMfaDevices(username: String): IO[Unit] =
          deferredDeleteMfaDevices.complete(username)

        override def deleteAccessKeys(username: String): IO[Unit] =
          deferredDeleteAccessKeys.complete(username)
      })
      res <- new IamUserCleanupLambda(resource).handleRequest(RequestParameters(Delete, physicalResourceId, username))
      deletedMfaUsername <- deferredDeleteMfaDevices.get
      deletedAccessKeyUsername <- deferredDeleteAccessKeys.get
    } yield {
      res should be(HandlerResponse(physicalResourceId, JsonObject("username"-> username.asJson)))
      deletedMfaUsername should be(username)
      deletedAccessKeyUsername should be(username)
    }
  }

}
