package owl.gateway

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Flow
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.io.StdIn
import owl.common.OwlService

import scala.util.{Failure, Success}

object GatewayServer extends OwlService with LazyLogging {
  override final val service = "gateway"

  final val config = ConfigFactory.load()
  final val Port = config.getInt("port")
  final val Host = config.getString("host")
  final val Ping = config.getString("pingRoute")
  final val Pong = "PONG"
  final val Ws = config.getString("wsRoute")
  final val WelcomeMessage = s"Welcome to $service"
  final val StartupMessage =
    s"$service is running at $Port ! Stop the server by pressing q"
  final val ShutdownMessage = s"Bye .. $service is shutting down"

  implicit val actorSystem: ActorSystem[Nothing] =
    ActorSystem[Nothing](Behaviors.empty, s"$service-actor-system")
  implicit val ec: ExecutionContext = actorSystem.executionContext

  def slashOrEmpty: Route =
    pathEndOrSingleSlash {
      reject
    }

  def pingRoute: Route =
    path(Ping) {
      complete(Pong)
    }

  def wsRoute: Route =
    path(Ws) {
      logger.info("got message")
      handleWebSocketMessages(echoFlow)
    }

  val echoFlow: Flow[Message, TextMessage.Strict, NotUsed] =
    Flow[Message].collect {
      case TextMessage.Strict(text) => TextMessage(text)
      case _                        => TextMessage("Unsupported")
    }

  override def run(): Unit = {
    val server = Http()
      .newServerAt(Host, Port)
      .bind(slashOrEmpty ~ pingRoute ~ wsRoute)
      .map(_.addToCoordinatedShutdown(hardTerminationDeadline = 10.seconds))

    server.onComplete {
      case Success(_binding) =>
        logger.info(StartupMessage)
      case Failure(exception) =>
        logger.error(s"Failed to bind $service, terminating", exception)
        actorSystem.terminate()
    }

    @tailrec
    def handleKeypress(): Unit =
      if (StdIn.readChar() == 'q') {
        server
          .flatMap(_.unbind())
          .onComplete(_ => actorSystem.terminate())
        logger.info(ShutdownMessage)
      } else handleKeypress()

    handleKeypress()
  }
}
