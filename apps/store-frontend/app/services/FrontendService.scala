package services

import akka.actor.Actor
import akka.pattern.pipe
import com.amazing.store.messages.{ChangeProductPrice, UpdateProduct, CreateProduct}
import com.amazing.store.services.{Directory, Client}
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext

/**
 *
 *
 * Created by adelegue on 13/06/2014.
 */
case class BroadCastUpdate(product: models.Product)
case class FragmentsProductUpdated(product: models.Product)

class FrontendService extends Actor {

  override def receive: Receive = {
    case msg @ CreateProduct(id, label, description, imageUrl, price) =>
      config.Env.logger.debug(s"CreateProduct msg : $id :: $msg")
      models.Product.mergeAndUpdate(id, label, description, imageUrl, price) map(p => BroadCastUpdate(p)) pipeTo self
      metrics.Messages.ping.close()
    case msg @ UpdateProduct(id, label, description, imageUrl, price) =>
      config.Env.logger.debug(s"UpdateProduct msg : $id :: msg")
      models.Product.mergeAndUpdate(id, label, description, imageUrl, price) map(p => BroadCastUpdate(p)) pipeTo self
      metrics.Messages.ping.close()
    case ChangeProductPrice(id, newPrice) =>
      config.Env.logger.debug(s"ChangeProductPrice msg : $id :: $newPrice")
      models.Product.mergeAndUpdate(id, null, null, null, newPrice) map(p => BroadCastUpdate(p)) pipeTo self
      metrics.Messages.ping.close()
    case BroadCastUpdate(p) =>
      config.Env.logger.debug(s"Broadcasting event : $p")
      Client().withRole(Directory.ROLE_FRONTEND).ref(Directory.FRONTEND_SERVICE_NAME) !!! FragmentsProductUpdated(p)
      self ! FragmentsProductUpdated(p)
      metrics.Messages.ping.close()
    case FragmentsProductUpdated(p) =>
      config.Env.logger.debug(s"Product to send to browser : $p")
      http.Feed.channel.get() push Json.toJson(p)
      metrics.Messages.ping.close()
    case _ @ msg => config.Env.logger.debug(s"Message non géré $msg")
  }


}