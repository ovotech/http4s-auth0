package com.ovoenergy.http4s.client.middleware.auth0

import java.net.ConnectException

import cats.data.{EitherT, Kleisli}
import cats.effect.IO
import cats.implicits._
import com.ovoenergy.http4s.client.middleware.auth0.Client.Error._
import org.http4s.circe._
import org.http4s.client.{Client => Http4sClient, DisposableResponse}
import org.http4s._

import scala.util.Try

/**
  * HTTP4s Client middleware that transparently provides Auth0 authentication
  *
  * @todo The request for a new token would be better if it could only ever be
  *       performing a single request so that multiple threads would not kick
  *       off multiple requests to the Auth0 backend to generate a new one if
  *       the token was not present or had become invalid.
  * @todo Clean up case-logic for retry into something neater
  */
class Client(val config: Config, val client: Http4sClient[IO]) {
  private implicit val authZeroErrorBodyEntityEncoder: EntityEncoder[IO, ErrorBody] = jsonEncoderOf

  import Client._

  def open(req: Request[IO]): IO[DisposableResponse[IO]] = {
    retryRequest(req, currentToken, 1).flatMap({
      case Right((response, token)) =>
        IO {
          currentToken = Some(token)
          response
        }
      case Left(err) =>
        IO(currentToken = None)
          .map(_ => errorResponse(err))
    })
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def retryRequest(req: Request[IO], maybeToken: Option[AuthZeroToken], retries: Int): IO[Result[ResponseAndToken]] = {
    val result: IO[Result[ResponseAndToken]] = (for {
      token <- EitherT(eitherToken(maybeToken))
      result <- EitherT(performRequest(req, token))
    } yield result).value

    result.flatMap({
      case Left(_) if retries > 0 => retryRequest(req, None, retries - 1)
      case Left(err) => IO.pure(err.asLeft[ResponseAndToken])
      case result@Right(_) => IO.pure(result)
    })
  }

  private def performRequest(req: Request[IO], token: AuthZeroToken): IO[Result[ResponseAndToken]] = {
    client.open(enhanceRequest(req, token)).flatMap(disposableResponse => {
      disposableResponse.response.status match {
        case Status.Unauthorized => requestNotAuthorized(disposableResponse)
        case Status.NotFound => requestNotAuthorized(disposableResponse)
        case _ => IO.pure((disposableResponse, token).asRight[Error])
      }
    })
  }

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  private def requestNotAuthorized(disposableResponse: DisposableResponse[IO]): IO[Result[ResponseAndToken]] = {
    IO {
      val _ = Try(disposableResponse.dispose.unsafeRunSync()) // TODO: log if this throws?
      NotAuthorized().asLeft[ResponseAndToken]
    }
  }

  private def eitherToken(maybeToken: Option[AuthZeroToken]): IO[Result[AuthZeroToken]] =
    maybeToken.map(token => IO.pure(token.asRight[Error])).getOrElse(generateToken())

  private def generateToken(): IO[Result[AuthZeroToken]] = {
    val request = TokenRequest(config.audience, config.id, config.secret)

    val uri: Uri = config.uri / "oauth" / "token"

    client
      .expect[TokenResponse](Request[IO](method = Method.POST, uri = uri).withEntity(request))
      .map(_.accessToken.asRight[Error])
      .handleError {
        case e: ConnectException => AuthZeroUnavailable(e).asLeft[AuthZeroToken]
        // TODO: could add more granularity to error handling here
        case _ => NotAuthorized().asLeft[AuthZeroToken]
      }
  }

  private def enhanceRequest(req: Request[IO], token: AuthZeroToken): Request[IO] = req.putHeaders(Header("Authorization", s"Bearer $token"))

  private def errorResponse(err: Error): DisposableResponse[IO] = {
    val status = err match {
      case NotAuthorized() => Status.Unauthorized
      case AuthZeroUnavailable(_) => Status.RequestTimeout
    }

    val entityResponse = Response[IO](status = status).withEntity(ErrorBody(err.msg))

    DisposableResponse(entityResponse, nullOpDispose)
  }

  private val nullOpDispose = IO.pure(())

  private var currentToken: Option[AuthZeroToken] = None
}

object Client {

  type ResponseAndToken = (DisposableResponse[IO], AuthZeroToken)

  type AuthZeroToken = String

  @SuppressWarnings(Array("org.wartremover.warts.Nothing"))
  def apply(config: Config)(client: Http4sClient[IO]): Http4sClient[IO] = {
    val authClient = new Client(config, client)

    def authenticatedOpen(req: Request[IO]): IO[DisposableResponse[IO]] = {
      authClient.open(req)
    }

    client.copy(open = Kleisli(authenticatedOpen))
  }

  sealed trait Error extends Product with Serializable {
    def msg: String
  }

  type Result[T] = Either[Error, T]

  object Error {

    final case class NotAuthorized() extends Error {
      val msg: String = "The credentials you presented have not been accepted by Auth0"
    }

    final case class AuthZeroUnavailable(cause: ConnectException) extends Error {
      val msg: String = s"Auth0 cannot be contacted to validate your credentials - ${cause.getMessage}"
    }

  }

}
