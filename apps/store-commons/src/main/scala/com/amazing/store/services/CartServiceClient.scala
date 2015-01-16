package com.amazing.store.services

import com.amazing.store.models.Order
import com.amazing.store.services.Directory._

import scala.concurrent.{Future, ExecutionContext}
import com.amazing.store.messages._
import com.amazing.store.messages.DeleteProduct
import com.amazing.store.messages.ChangeProductPrice
import com.amazing.store.messages.GetNumberOfItemsInCartResponse
import com.amazing.store.messages.GetNumberOfItemsInCart
import com.distributedstuff.services.clients.akkasupport.AkkaClientSupport

object CartServiceClient {

  def listAllOrders()(implicit ec: ExecutionContext): Future[List[Order]] = {
    ServiceRegistry.registry().akkaClient(Directory.CART_SERVICE_NAME)
        .ask[GetAllOrdersResponse](GetAllOrdersRequest).map {
        case Some(GetAllOrdersResponse(orders)) => orders
        case _ => List()
      } recoverWith {
        case _ => Future.successful(List())
      }
  }

}
