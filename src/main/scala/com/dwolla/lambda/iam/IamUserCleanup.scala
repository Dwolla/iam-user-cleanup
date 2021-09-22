package com.dwolla.lambda.iam

import cats.data._
import cats.effect._
import cats.syntax.all._
import com.dwolla.lambda.cloudformation.CloudFormationRequestType._
import com.dwolla.lambda.cloudformation._
import com.dwolla.lambda.iam.IamUserCleanupLambda.RequestValidation.requestTypeToAction
import com.dwolla.lambda.iam.model._
import fs2._
import io.circe._
import io.circe.syntax._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.iam.IamAsyncClient
import software.amazon.awssdk.services.iam.model._

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

  implicit class RequestValidation(val req: CloudFormationCustomResourceRequest) extends AnyVal {
    def validatedResourceProperty[T : Decoder](property: String): EitherNel[RequestValidationError, T] =
      (for {
        resourceProperties <- req.ResourceProperties.toRight(MissingResourcePropertiesValidationError)
        json <- resourceProperties(property).toRight(MissingResourceProperty(property))
        t <- json.as[T].leftMap(InvalidUsername(json, _))
      } yield t).toEitherNel

    def validateUsername: EitherNel[RequestValidationError, String] =
      validatedResourceProperty[String]("username")

    def validateUserArn: EitherNel[RequestValidationError, String] =
      validatedResourceProperty[String]("userArn")

    def validatePhysicalResourceId: EitherNel[RequestValidationError, PhysicalResourceId] = {
      val inboundPhysicalId = req.RequestType -> req.PhysicalResourceId match {
        case (CreateRequest, None) => None.rightNel
        case (UpdateRequest | DeleteRequest, Some(id: PhysicalResourceId)) => Some(id).rightNel
        case (CreateRequest, _) => CreateRequestWithPhysicalResourceId.leftNel
        case _ => MissingPhysicalResourceId.leftNel
      }

      (inboundPhysicalId, validateUserArn.map(tagPhysicalResourceId)).parMapN(_ getOrElse _)
    }

    def validated: EitherNel[RequestValidationError, RequestParameters] =
      (requestTypeToAction(req.RequestType).asRight, validatePhysicalResourceId, validateUsername).parMapN(RequestParameters)
  }

}

class IamUserCleanupLambda[F[_] : ConcurrentEffect](iamResource: Resource[F, IamAlg[F]]) {
  import IamUserCleanupLambda._

  def raiseErrors(nel: NonEmptyList[RequestValidationError]): F[HandlerResponse] =
    RequestValidationException(nel).raiseError[F, HandlerResponse]

  private def handlerResponse(physicalResourceId: PhysicalResourceId, username: String): HandlerResponse =
    HandlerResponse(physicalResourceId, JsonObject("username"-> username.asJson))

  val handleRequest: RequestParameters => F[HandlerResponse] = {
    case RequestParameters(NoOp, physicalResourceId, username) =>
      handlerResponse(physicalResourceId, username).pure[F]
    case RequestParameters(Delete, physicalResourceId, username) =>
      iamResource.use { iam =>
        val delMfa = Kleisli(iam.deleteMfaDevices)
        val delAK = Kleisli(iam.deleteAccessKeys)
        val delSshKeys = Kleisli(iam.deleteSshKeys)

        (delMfa, delAK, delSshKeys)
          .mapN(_ |+| _ |+| _)
          .run(username)
          .map(_ => handlerResponse(physicalResourceId, username))
      }
  }

  def handleRequest(req: CloudFormationCustomResourceRequest): F[HandlerResponse] =
    req.validated.fold(raiseErrors, handleRequest)

}

trait IamAlg[F[_]] {
  def deleteMfaDevices(username: String): F[Unit]
  def deleteAccessKeys(username: String): F[Unit]
  def deleteSshKeys(username: String): F[Unit]
}

object IamAlg {

  private def acquireIamClient[F[_] : Sync]: F[IamAsyncClient] = Sync[F].delay {
    IamAsyncClient.builder().region(Region.AWS_GLOBAL).build()
  }

  private def shutdownIamClient[F[_] : Sync](client: IamAsyncClient): F[Unit] =
    Sync[F].delay(client.close())

  def resource[F[_] : ConcurrentEffect]: Resource[F, IamAlg[F]] =
    Resource.make(acquireIamClient[F] <* Sync[F].delay(println("acquired IAM client")) )(shutdownIamClient[F](_) <* Sync[F].delay(println("IAM client shut down")))
      .map(new IamAlgImpl[F](_))

  class IamAlgImpl[F[_] : ConcurrentEffect](client: IamAsyncClient) extends IamAlg[F] {
    import com.dwolla.aws.AwsPagination._

    private val void: Any => Unit = _ => ()

    private def listUserMfaDevices(username: String): Stream[F, MFADevice] =
      unfold(client.listMFADevicesPaginator(ListMfaDevicesRequest.builder().userName(username).build()))(_.mfaDevices())

    override def deleteMfaDevices(username: String): F[Unit] =
      listUserMfaDevices(username)
        .map(_.serialNumber())
        .flatMap(sn => deactivateMfaDevice(username, sn) >> deleteMfaDevice(sn).attempt.void)
        .compile
        .drain

    private def listUserAccessKeys(username: String): Stream[F, AccessKeyMetadata] =
      unfold(client.listAccessKeysPaginator(ListAccessKeysRequest.builder().userName(username).build()))(_.accessKeyMetadata)

    override def deleteAccessKeys(username: String): F[Unit] =
      listUserAccessKeys(username)
        .map(_.accessKeyId())
        .flatMap(deleteAccessKey(username, _))
        .compile
        .drain

    private def deleteMfaDevice(serialNumber: String): Stream[F, Unit] = {
      eval[F](DeleteVirtualMfaDeviceRequest.builder().serialNumber(serialNumber).build())(client.deleteVirtualMFADevice)(void)
    }

    private def deactivateMfaDevice(username: String, serialNumber: String): Stream[F, Unit] =
      eval[F](DeactivateMfaDeviceRequest.builder().serialNumber(serialNumber).userName(username).build())(client.deactivateMFADevice)(void)

    private def deleteAccessKey(username: String, accessKeyId: String): Stream[F, Unit] =
      eval[F](DeleteAccessKeyRequest.builder().accessKeyId(accessKeyId).userName(username).build())(client.deleteAccessKey)(void)

    private def listSshPublicKeysForUser(username: String): Stream[F, SSHPublicKeyMetadata] =
      unfold(client.listSSHPublicKeysPaginator(ListSshPublicKeysRequest.builder().userName(username).build()))(_.sshPublicKeys())

    private def deleteSshPublicKey(username: String, sshKeyId: String): Stream[F, Unit] =
      eval[F](DeleteSshPublicKeyRequest.builder().userName(username).sshPublicKeyId(sshKeyId).build())(client.deleteSSHPublicKey)(void)

    override def deleteSshKeys(username: String): F[Unit] =
      listSshPublicKeysForUser(username)
        .map(_.sshPublicKeyId())
        .flatMap(deleteSshPublicKey(username, _))
        .compile
        .drain

  }

}
