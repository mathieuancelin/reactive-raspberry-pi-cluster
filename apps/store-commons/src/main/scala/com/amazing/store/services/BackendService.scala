package com.amazing.store.services


import com.amazing.store.messages.BackendService.{NewOrder, ListProductsPrices}
import com.amazing.store.models.Order
import com.amazing.store.services.Directory.{ROLE_BACKEND, BACKEND_SERVICE_NAME}
import com.distributedstuff.services.clients.akkasupport.AkkaClientSupport

import scala.concurrent.{ExecutionContext, Future}

object BackendService {

  def listProductPrices(ids: List[String])(implicit ec: ExecutionContext): Future[List[(String, Double)]] = {
    ServiceRegistry.registry().akkaClient(BACKEND_SERVICE_NAME)
      .ask[List[(String, Double)]] (ListProductsPrices(ids)).map(o => o.getOrElse(List()))
  }

  def notifyNewOrder(order: Order)(implicit ec: ExecutionContext): Future[Unit] = {
    ServiceRegistry
      .registry()
      .akkaClient(BACKEND_SERVICE_NAME)
      .tell(NewOrder(order))
    Future.successful(())
  }

}
