package model

import java.util.UUID

import model.OnlineStatus.OnlineStatus
import play.api.libs.json.{Format, Json}

object OnlineStatus extends Enumeration {
  type OnlineStatus = Value
  val Offline, Available, Live = Value
  implicit val enumTypeFormat  = EnumUtils.enumFormat(OnlineStatus)
}

case class User(userId: UUID, firstName: String, onlineStatus: OnlineStatus = OnlineStatus.Offline)
object User {
  implicit val format: Format[User] = Json.format
}
