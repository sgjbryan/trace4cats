package io.janstenpickle.trace4cats.http4s.server

import cats.Monad
import cats.effect.BracketThrow
import cats.syntax.applicative._
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.http4s.common.{Http4sRequestFilter, Http4sSpanNamer, Request_}
import io.janstenpickle.trace4cats.inject.EntryPoint
import org.http4s._
import org.http4s.util.CaseInsensitiveString

trait ServerSyntax {
  implicit class TracedRoutes[F[_], G[_]](routes: HttpRoutes[G]) {
    def inject(
      entryPoint: EntryPoint[F],
      spanNamer: Http4sSpanNamer = Http4sSpanNamer.methodWithPath,
      requestFilter: Http4sRequestFilter = Http4sRequestFilter.allowAll,
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Span[F]], F: BracketThrow[F]): HttpRoutes[F] =
      ServerTracer
        .injectRoutes(routes, entryPoint, spanNamer, requestFilter, dropHeadersWhen, (_, s) => s.pure[F])

    def injectContext[Ctx](
      entryPoint: EntryPoint[F],
      makeContext: (Request_, Span[F]) => F[Ctx],
      spanNamer: Http4sSpanNamer = Http4sSpanNamer.methodWithPath,
      requestFilter: Http4sRequestFilter = Http4sRequestFilter.allowAll,
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Ctx], F: BracketThrow[F]): HttpRoutes[F] =
      ServerTracer
        .injectRoutes(routes, entryPoint, spanNamer, requestFilter, dropHeadersWhen, makeContext)
  }

  implicit class TracedHttpApp[F[_], G[_]: Monad](app: HttpApp[G]) {
    def inject(
      entryPoint: EntryPoint[F],
      spanNamer: Http4sSpanNamer = Http4sSpanNamer.methodWithPath,
      requestFilter: Http4sRequestFilter = Http4sRequestFilter.allowAll,
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Span[F]], F: BracketThrow[F]): HttpApp[F] =
      ServerTracer
        .injectApp(app, entryPoint, spanNamer, requestFilter, dropHeadersWhen, (_, s) => s.pure[F])

    def injectContext[Ctx](
      entryPoint: EntryPoint[F],
      makeContext: (Request_, Span[F]) => F[Ctx],
      spanNamer: Http4sSpanNamer = Http4sSpanNamer.methodWithPath,
      requestFilter: Http4sRequestFilter = Http4sRequestFilter.allowAll,
      dropHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains
    )(implicit P: Provide[F, G, Ctx], F: BracketThrow[F]): HttpApp[F] =
      ServerTracer
        .injectApp(app, entryPoint, spanNamer, requestFilter, dropHeadersWhen, makeContext)
  }
}
