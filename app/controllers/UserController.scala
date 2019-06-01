package controllers

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated}
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.Timeout
import controllers.StateActor.RegisterWebSocketClient
import javax.inject.{Inject, _}
import model.{OnlineStatus, User}
import play.api.libs.json.{Format, JsValue, Json}
import play.api.libs.streams.ActorFlow
import play.api.mvc._

import scala.collection.immutable.HashMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * This controller creates an `Action` to handle HTTP requests to the
  * application's home page.
  */
@Singleton
class UserController @Inject()(cc: ControllerComponents)(implicit system: ActorSystem,
                                                         executionContext: ExecutionContext,
                                                         mat: Materializer)
    extends AbstractController(cc) {

  implicit val timeout = Timeout(1000 milliseconds)

  val stateActor = system.actorOf(StateActor.props, "stateActor")

  /**
    * Create an Action to render an HTML page.
    *
    * The configuration in the `routes` file means that this method
    * will be called when the application receives a `GET` request with
    * a path of `/`.
    */
  def createUser = Action.async(parse.json[CreateUser]) { implicit request =>
    for {
      userId: UUID <- (stateActor ? request.body).mapTo[UUID]
    } yield Ok(Json.obj("success" -> true, "userId" -> userId))
  }

  def getAllUsers = Action.async {
    for {
      users: Seq[User] <- (stateActor ? GetAllUsers).mapTo[Seq[User]]
    } yield Ok(Json.toJson(users))
  }

  def socket(userId: String) = WebSocket.accept[JsValue, JsValue] { request =>
    val userIdUuid = UUID.fromString(userId)
    ActorFlow.actorRef { out =>
      WebSocketActor.props(out, stateActor, userIdUuid)
    }
  }

}

case class CreateUser(firstName: String)
object CreateUser {
  implicit val format: Format[CreateUser] = Json.format
}

case object GetAllUsers

class StateActor extends Actor with ActorLogging {

  private var users: Map[UUID, User]                = HashMap.empty
  private var webSocketClients: Map[ActorRef, UUID] = HashMap.empty

  def sendToAllClients(jsValue: JsValue): Unit = webSocketClients.keys.foreach(out => out ! jsValue)

  override def receive: Receive = {
    case CreateUser(firstName) =>
      val userId = UUID.randomUUID()
      val user   = User(userId = userId, firstName = firstName)
      users = users + (userId -> user)
      sender ! userId
      sendToAllClients(Json.toJson(user))

    case GetAllUsers => sender ! users.values.toSeq
    case RegisterWebSocketClient(userId) =>
      log.info(s"Client registered: $userId")
      if (users.contains(userId)) {
        context.watch(sender)

        webSocketClients = webSocketClients + (sender -> userId)
        users = users.updated(userId, users(userId).copy(onlineStatus = OnlineStatus.Live))
      }

    case Terminated(ref) => {
      val userIdOpt = webSocketClients.get(ref)
      userIdOpt.foreach(
        userId =>
          if (users.contains(userId))
            users = users.updated(userId, users(userId).copy(onlineStatus = OnlineStatus.Live)))
    }

  }
}

object StateActor {
  def props = Props(new StateActor())

  case class RegisterWebSocketClient(userId: UUID)
}

class WebSocketActor(out: ActorRef, stateActor: ActorRef, userId: UUID)
    extends Actor
    with ActorLogging {

  import context.dispatcher
  override def preStart(): Unit = {
    super.preStart()
    stateActor ! RegisterWebSocketClient(userId)
    context.system.scheduler.schedule(30 seconds, 30 seconds, out, Json.obj("eventType" -> "PING"))

  }

  override def receive: Receive = {

    case js: JsValue =>
      log.info(s"Received : $js")
      out ! js
  }
}
object WebSocketActor {
  def props(out: ActorRef, stateActor: ActorRef, userId: UUID) =
    Props(new WebSocketActor(out, stateActor, userId: UUID))

}

sealed trait StreamMessage
