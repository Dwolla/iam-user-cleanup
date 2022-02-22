package com.dwolla.lambda.iam

import cats.effect._
import cats.syntax.all._
import cats.tagless.Derive
import cats.tagless.aop.Instrument
import com.dwolla.fs2aws.AwsEval
import fs2.Stream
import org.typelevel.log4cats.Logger
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.iam.IamAsyncClient
import software.amazon.awssdk.services.iam.model._

trait IamAlg[F[_]] {
  def deleteMfaDevices(username: String): F[Unit]
  def deleteAccessKeys(username: String): F[Unit]
  def deleteSshKeys(username: String): F[Unit]
}

object IamAlg {
  implicit val instrumentation: Instrument[IamAlg] = Derive.instrument

  private def acquireIamClient[F[_] : Sync]: F[IamAsyncClient] = Sync[F].delay {
    IamAsyncClient.builder().region(Region.AWS_GLOBAL).build()
  }

  private def shutdownIamClient[F[_] : Sync](client: IamAsyncClient): F[Unit] =
    Sync[F].delay(client.close())

  def resource[F[_] : Async : Logger]: Resource[F, IamAlg[F]] =
    Resource.make(acquireIamClient[F] <* Logger[F].info("acquired IAM client"))(shutdownIamClient[F](_) <* Logger[F].info("IAM client shut down"))
      .map(new IamAlgImpl[F](_))

  class IamAlgImpl[F[_] : Async](client: IamAsyncClient) extends IamAlg[F] {
    private val void: Any => Unit = _ => ()

    private def listUserMfaDevices(username: String): Stream[F, MFADevice] =
      AwsEval.unfold(client.listMFADevicesPaginator(ListMfaDevicesRequest.builder().userName(username).build()))(_.mfaDevices())

    override def deleteMfaDevices(username: String): F[Unit] =
      listUserMfaDevices(username)
        .map(_.serialNumber())
        .evalMap(sn => deactivateMfaDevice(username, sn) >> deleteMfaDevice(sn).attempt.void)
        .compile
        .drain

    private def listUserAccessKeys(username: String): Stream[F, AccessKeyMetadata] =
      AwsEval.unfold(client.listAccessKeysPaginator(ListAccessKeysRequest.builder().userName(username).build()))(_.accessKeyMetadata)

    override def deleteAccessKeys(username: String): F[Unit] =
      listUserAccessKeys(username)
        .map(_.accessKeyId())
        .evalMap(deleteAccessKey(username, _))
        .compile
        .drain

    private def deleteMfaDevice(serialNumber: String): F[Unit] =
      AwsEval.eval[F](DeleteVirtualMfaDeviceRequest.builder().serialNumber(serialNumber).build())(client.deleteVirtualMFADevice)(void)

    private def deactivateMfaDevice(username: String, serialNumber: String): F[Unit] =
      AwsEval.eval[F](DeactivateMfaDeviceRequest.builder().serialNumber(serialNumber).userName(username).build())(client.deactivateMFADevice)(void)

    private def deleteAccessKey(username: String, accessKeyId: String): F[Unit] =
      AwsEval.eval[F](DeleteAccessKeyRequest.builder().accessKeyId(accessKeyId).userName(username).build())(client.deleteAccessKey)(void)

    private def listSshPublicKeysForUser(username: String): Stream[F, SSHPublicKeyMetadata] =
      AwsEval.unfold(client.listSSHPublicKeysPaginator(ListSshPublicKeysRequest.builder().userName(username).build()))(_.sshPublicKeys())

    private def deleteSshPublicKey(username: String, sshKeyId: String): F[Unit] =
      AwsEval.eval[F](DeleteSshPublicKeyRequest.builder().userName(username).sshPublicKeyId(sshKeyId).build())(client.deleteSSHPublicKey)(void)

    override def deleteSshKeys(username: String): F[Unit] =
      listSshPublicKeysForUser(username)
        .map(_.sshPublicKeyId())
        .evalMap(deleteSshPublicKey(username, _))
        .compile
        .drain

  }

}
