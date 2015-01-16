package com.amazing.store.services

import com.amazing.store.messages.{ChangeProductPrice, CreateProduct, UpdateProduct}
import com.amazing.store.services.Directory.FRONTEND_SERVICE_NAME
import com.distributedstuff.services.clients.akkasupport.AkkaClientSupport

object FrontendServiceClient {

  def createProduct(id:String, label:String, description:String, imageUrl:String, price: Double) = {
    ServiceRegistry.registry().akkaClient(FRONTEND_SERVICE_NAME) ! CreateProduct(id, label, description, imageUrl, price)
  }

  def updateProduct(id:String, label:String, description:String, imageUrl:String, price: Double) = {
    ServiceRegistry.registry().akkaClient(FRONTEND_SERVICE_NAME) ! UpdateProduct(id, label, description, imageUrl, price)
  }

  def updatePrice(id: String, newPrice: Double) = {
    ServiceRegistry.registry().akkaClient(FRONTEND_SERVICE_NAME) ! ChangeProductPrice(id, newPrice)
  }

}
