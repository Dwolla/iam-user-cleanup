package com.dwolla.lambda.iam

import cats.Applicative
import cats.effect._
import cats.syntax.all._
import com.dwolla.lambda.iam.model.Username
import feral.lambda.cloudformation.CloudFormationRequestType.{CreateRequest, DeleteRequest, UpdateRequest}
import feral.lambda.cloudformation._
import feral.lambda.{LambdaEnv, TestContext}
import io.circe._
import io.circe.syntax._
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.cats.implicits._
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen}

class HandleRequestSpec
  extends CatsEffectSuite
    with ScalaCheckEffectSuite {

  private def genEnv[F[_] : Applicative, E : Arbitrary]: Gen[LambdaEnv[F, E]] =
    for {
      e <- arbitrary[E]
    } yield LambdaEnv.pure(e, TestContext[F])
  private implicit def arbEnv[F[_] : Applicative, E : Arbitrary]: Arbitrary[LambdaEnv[F, E]] = Arbitrary(genEnv[F, E])

  test("HandleRequest") {
    implicit val jsonObject: Arbitrary[JsonObject] = Arbitrary {
      for {
        arn <- CloudFormationCustomResourceArbitraries.genPhysicalResourceId
        username <- CloudFormationCustomResourceArbitraries.genPhysicalResourceId
      } yield JsonObject(
        "userArn" -> arn.asJson,
        "username" -> username.asJson,
      )
    }

    val genCloudFormationCustomResourceRequest =
      Gen.oneOf[CloudFormationRequestType](CreateRequest, UpdateRequest, DeleteRequest)
        .mproduct[Option[PhysicalResourceId]] {
          case CreateRequest =>
            Gen.const(none)
         case UpdateRequest | DeleteRequest =>
           CloudFormationCustomResourceArbitraries.genPhysicalResourceId.map(_.some)
          case _ => Gen.fail
        }
        .flatMap { case (cfrt, opri) =>
          CloudFormationCustomResourceArbitraries
            .genCloudFormationCustomResourceRequest[JsonObject]
            .map(_.copy(RequestType = cfrt, PhysicalResourceId = opri))
        }
    implicit val arbCloudFormationCustomResourceRequest: Arbitrary[CloudFormationCustomResourceRequest[JsonObject]] = Arbitrary(genCloudFormationCustomResourceRequest)

    PropF.forAllF { implicit env: LambdaEnv[IO, CloudFormationCustomResourceRequest[JsonObject]] =>
      (for {
        refDeleteMfaDevices <- Ref[IO].of(none[String])
        refDeleteAccessKeys <- Ref[IO].of(none[String])
        refDeleteSshKeys <- Ref[IO].of(none[String])
        iamAlg = new IamAlg[IO] {
          override def deleteMfaDevices(username: String): IO[Unit] =
            refDeleteMfaDevices.set(username.some)

          override def deleteAccessKeys(username: String): IO[Unit] =
            refDeleteAccessKeys.set(username.some)

          override def deleteSshKeys(username: String): IO[Unit] =
            refDeleteSshKeys.set(username.some)
        }
        req <- env.event
        res <- req.RequestType match {
          case CreateRequest => IamUserCleanupLambda.handleRequest(iamAlg).createResource(req.ResourceProperties)
          case UpdateRequest => IamUserCleanupLambda.handleRequest(iamAlg).updateResource(req.ResourceProperties)
          case DeleteRequest => IamUserCleanupLambda.handleRequest(iamAlg).deleteResource(req.ResourceProperties)
          case other => IO.raiseError(ArbitraryDataException(other))
        }
        deletedMfaUsername <- refDeleteMfaDevices.get
        deletedAccessKeyUsername <- refDeleteAccessKeys.get
        deferredDeleteSshKeyUsername <- refDeleteSshKeys.get
      } yield {
        val maybeExpectedPhysicalResourceId = req.RequestType match {
          case CreateRequest =>
            req.ResourceProperties("userArn").flatMap(_.as[PhysicalResourceId].toOption)
          case UpdateRequest | DeleteRequest =>
            req.PhysicalResourceId
          case other => throw ArbitraryDataException(other)
        }

        val expectedUsername = req.ResourceProperties.asJson.as[Username].toOption.get

        assertEquals(res, maybeExpectedPhysicalResourceId.map(HandlerResponse(_, expectedUsername.some)).get)

        req.RequestType match {
          case DeleteRequest =>
            assertEquals(deletedMfaUsername, expectedUsername.username.some)
            assertEquals(deletedAccessKeyUsername, expectedUsername.username.some)
            assertEquals(deferredDeleteSshKeyUsername, expectedUsername.username.some)
          case _ =>
            assertEquals(deletedMfaUsername, None)
            assertEquals(deletedAccessKeyUsername, None)
            assertEquals(deferredDeleteSshKeyUsername, None)
        }
      }).handleErrorWith(env.event.map(_.asJson.spaces2).flatMap(IO.println) >> _.raiseError[IO, Unit])
    }
  }
}

case class ArbitraryDataException[A](a: A) extends RuntimeException(s"this case should never occur because the generator is not supposed to emit values of $a")
