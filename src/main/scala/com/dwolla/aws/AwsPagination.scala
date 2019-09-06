package com.dwolla.aws

import java.util.concurrent._

import cats.data._
import cats.effect._
import cats.implicits._
import fs2._
import fs2.interop.reactivestreams.fromPublisher
import org.reactivestreams.Publisher

import scala.language.reflectiveCalls

//noinspection AccessorLikeMethodIsEmptyParen
object AwsPagination {
  private sealed trait PaginationState
  private case object FirstPage extends PaginationState
  private case class HasNextPage(token: String) extends PaginationState
  private case object PaginationComplete extends PaginationState

  private implicit class PaginationStateOps(val ps: PaginationState) extends AnyVal {
    def toEither: Either[Unit, Option[String]] = ps match {
      case FirstPage => Right(None)
      case HasNextPage(marker) => Right(Option(marker))
      case PaginationComplete => Left(())
    }

    def fold[Req, Builder <: RequestBuilder[Req, Builder]](builder: Builder): Option[Req] =
      ps.toEither.map(_.foldl(builder)(_ marker _).build())
        .toOption
  }

  private abstract class Paginate[F[_] : ConcurrentEffect, O] {
    type Req
    type Builder <: RequestBuilder[Req, Builder]
    type Res <: Result
    import scala.jdk.CollectionConverters._

    def apply(builder: => Builder, client: Req => CompletableFuture[Res], extractor: Res => java.lang.Iterable[O]): Stream[F, O] = {
      val sm: PaginationState => F[Option[Res]] =
        _.fold[Req, Builder](builder)
          .map(req => cfToF[F](client(req)))
          .sequence

      val stateMatcher = sm andThen OptionT.apply

      val resToEvalStep: Res => (Chunk[O], PaginationState) = res =>
        (Chunk.iterable(extractor(res).asScala), if (res.isTruncated()) HasNextPage(res.marker()) else PaginationComplete)

      Stream.unfoldChunkEval[F, PaginationState, O](FirstPage)(stateMatcher(_).map(resToEvalStep).value)
    }
  }

  type RequestBuilder[Req, B <: RequestBuilder[Req, B]] = {
    def marker(s: String): B
    def build(): Req
  }

  type Result = {
    def marker(): String
    def isTruncated(): java.lang.Boolean
  }

  class PartiallyAppliedUnfoldF[F[_], Req1] {
    def apply[Builder1 <: RequestBuilder[Req1, Builder1],
      Res1 <: Result,
      O]
    (builder: => Builder1, client: Req1 => CompletableFuture[Res1])
    (extractor: Res1 => java.lang.Iterable[O])
    (implicit ev: ConcurrentEffect[F]): Stream[F, O] = (new Paginate[F, O] {
      override type Req = Req1
      override type Builder = Builder1
      override type Res = Res1
    }) (builder, client, extractor)
  }

  class PartiallyAppliedEvalF[F[_]] {
    def apply[Req, Res, O](req: => Req)
                          (client: Req => CompletableFuture[Res])
                          (extractor: Res => O)
                          (implicit ev: ConcurrentEffect[F]): Stream[F, O] =
      Stream.eval(cfToF[F](client(req))).map(extractor)
  }

  class PartiallyAppliedFromPublisherFRes[F[_], Res](publisher: Publisher[Res]) {
    import scala.jdk.CollectionConverters._

    private def toStream[T](res: Res => java.lang.Iterable[T]): Res => Stream[F, T] =
      res andThen (_.asScala) andThen Chunk.iterable andThen Stream.chunk

    def apply[O](extractor: Res => java.lang.Iterable[O])
                (implicit ev: ConcurrentEffect[F]): Stream[F, O] =
      fromPublisher[F, Res](publisher)
        .flatMap(toStream(extractor))

  }

  class PartiallyAppliedFromPublisherF[F[_]] {
    def apply[Res](publisher: Publisher[Res]) =
      new PartiallyAppliedFromPublisherFRes[F, Res](publisher)
  }

  def unfold[F[_], Req] = new PartiallyAppliedUnfoldF[F, Req]

  def unfold[F[_]] = new PartiallyAppliedFromPublisherF[F]

  def eval[F[_]] = new PartiallyAppliedEvalF[F]

  private class PartialCompletableFutureToF[F[_]] {
    def apply[A](makeCf: => CompletableFuture[A])
                (implicit ev: ConcurrentEffect[F]): F[A] =
      Concurrent.cancelableF[F, A] { cb =>
        val cf = makeCf
        cf.handle[Unit]((result: A, err: Throwable) => {
          err match {
            case null =>
              cb(Right(result))
            case _: CancellationException =>
              ()
            case ex: CompletionException if ex.getCause ne null =>
              cb(Left(ex.getCause))
            case ex =>
              cb(Left(ex))
          }
        })

        val cancelToken: CancelToken[F] = Sync[F].delay(cf.cancel(true)).void
        cancelToken.pure[F]
      }

  }

  private def cfToF[F[_]] = new PartialCompletableFutureToF[F]

}

