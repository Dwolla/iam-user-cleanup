package com.dwolla.lambda.iam

import _root_.io.circe._
import cats._
import cats.data._
import cats.effect._
import cats.effect.std.Random
import cats.effect.syntax.all._
import cats.syntax.all._
import cats.tagless.syntax.all._
import com.dwolla.lambda.iam.IamUserCleanupLambda.RequestValidation.requestTypeToAction
import com.dwolla.lambda.iam.IamUserCleanupLambda.handleRequest
import com.dwolla.lambda.iam.model._
import feral.lambda._
import feral.lambda.cloudformation.CloudFormationRequestType._
import feral.lambda.cloudformation._
import natchez.Span
import natchez.http4s.NatchezMiddleware
import natchez.xray.{XRay, XRayEnvironment}
import org.http4s.client.{Client, middleware}
import org.http4s.ember.client.EmberClientBuilder
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.iam.IamAsyncClient

object IamUserCleanupLambda {
  def acquireIamClient[F[_] : Sync]: F[IamAsyncClient] = Sync[F].delay {
    IamAsyncClient.builder().region(Region.AWS_GLOBAL).build()
  }

  def shutdownIamClient[F[_] : Sync](client: IamAsyncClient): F[Unit] = Sync[F].delay(client.close())

  object RequestValidation {
    val requestTypeToAction: CloudFormationRequestType => Action = {
      case CreateRequest | UpdateRequest | OtherRequestType(_) => NoOp
      case DeleteRequest => Delete
    }
  }

  implicit class RequestValidation(val req: CloudFormationCustomResourceRequest[JsonObject]) extends AnyVal {
    def validatedResourceProperty[T : Decoder](property: String): EitherNel[RequestValidationError, T] =
      (for {
        json <- req.ResourceProperties(property).toRight(MissingResourceProperty(property))
        t <- json.as[T].leftMap(InvalidUsername(json, _))
      } yield t).toEitherNel

    def validateUsername: EitherNel[RequestValidationError, Username] =
      validatedResourceProperty[String]("username").map(Username(_))

    def validateUserArn: EitherNel[RequestValidationError, PhysicalResourceId] =
      validatedResourceProperty[String]("userArn")
        .flatMap(PhysicalResourceId(_).toRightNel(InvalidPhysicalResourceId))

    def validatePhysicalResourceId: EitherNel[RequestValidationError, PhysicalResourceId] = {
      val inboundPhysicalId = req.RequestType -> req.PhysicalResourceId match {
        case (CreateRequest, None) => None.rightNel
        case (UpdateRequest | DeleteRequest, Some(id)) => Some(id).rightNel
        case (CreateRequest, _) => CreateRequestWithPhysicalResourceId.leftNel
        case _ => MissingPhysicalResourceId.leftNel
      }

      (inboundPhysicalId, validateUserArn).parMapN(_ getOrElse _)
    }

    def validated: EitherNel[RequestValidationError, RequestParameters] =
      (requestTypeToAction(req.RequestType).rightNel, validatePhysicalResourceId, validateUsername).parMapN(RequestParameters)
  }

  private def raiseErrors[F[_] : ApplicativeThrow](nel: NonEmptyList[RequestValidationError]): F[HandlerResponse[Username]] =
    RequestValidationException(nel).raiseError[F, HandlerResponse[Username]]

  private def handlerResponse(physicalResourceId: PhysicalResourceId, username: Username): HandlerResponse[Username] =
    HandlerResponse(physicalResourceId, username.some)

  private def handleRequest[F[_] : ApplicativeThrow](iam: IamAlg[F]): RequestParameters => F[HandlerResponse[Username]] = {
    case RequestParameters(NoOp, physicalResourceId, username) =>
      handlerResponse(physicalResourceId, username).pure[F]
    case RequestParameters(Delete, physicalResourceId, username) =>
        val delMfa = Kleisli(iam.deleteMfaDevices)
        val delAK = Kleisli(iam.deleteAccessKeys)
        val delSshKeys = Kleisli(iam.deleteSshKeys)

        (delMfa, delAK, delSshKeys)
          .mapN(_ |+| _ |+| _)
          .local[Username](_.username)
          .run(username)
          .map(_ => handlerResponse(physicalResourceId, username))
  }

  def handleRequest[F[_] : MonadThrow](iam: IamAlg[F])
                                      (implicit L: LambdaEnv[F, CloudFormationCustomResourceRequest[JsonObject]]): CloudFormationCustomResource[F, JsonObject, Username] = new CloudFormationCustomResource[F, JsonObject, Username] {
    private val handle: F[HandlerResponse[Username]] =
      for {
        req <- LambdaEnv[F, CloudFormationCustomResourceRequest[JsonObject]].event
        resp <- req.validated.fold(raiseErrors[F], handleRequest(iam))
      } yield resp

    override def createResource(input: JsonObject): F[HandlerResponse[Username]] = handle
    override def updateResource(input: JsonObject): F[HandlerResponse[Username]] = handle
    override def deleteResource(input: JsonObject): F[HandlerResponse[Username]] = handle
  }

}

class IamUserCleanupLambda[F[_] : Async](iamResource: Resource[F, IamAlg[F]]) {
  def handler: Resource[F, LambdaEnv[F, CloudFormationCustomResourceRequest[JsonObject]] => F[Option[INothing]]] =
    for {
      implicit0(random: Random[F]) <- Random.scalaUtilRandom[F].toResource
      entryPoint <- XRayEnvironment[Resource[F, *]].daemonAddress.flatMap {
        case Some(addr) => XRay.entryPoint(addr)
        case None => XRay.entryPoint[F]()
      }
      client <- httpClient
      iam <- iamResource
    } yield { implicit env: LambdaEnv[F, CloudFormationCustomResourceRequest[JsonObject]] =>
      TracedHandler(entryPoint, Kleisli { (span: Span[F]) =>
        CloudFormationCustomResource[Kleisli[F, Span[F], *], JsonObject, Username](tracedHttpClient(client, span), handleRequest(iam.mapK(Kleisli.liftK))).run(span)
      })
    }

  protected def httpClient: Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .build
      .map(middleware.Logger[F](logHeaders = true, logBody = true))

  private def tracedHttpClient(client: Client[F], span: Span[F]): Client[Kleisli[F, Span[F], *]] =
    NatchezMiddleware.client(client.translate(Kleisli.liftK[F, Span[F]])(Kleisli.applyK(span)))

  /**
   * The XRay kernel comes from environment variables, so we don't need to extract anything from the incoming event
   */
  private implicit def kernelSource[Event]: KernelSource[Event] = KernelSource.emptyKernelSource
}
