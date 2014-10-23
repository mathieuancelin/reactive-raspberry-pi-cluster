package com.amazing.store.services

import com.amazing.store.messages.{ChangeProductPrice, CreateProduct, UpdateProduct}

object FrontendServiceClient {

  def createProduct(id:String, label:String, description:String, imageUrl:String, price: Double) = {
    Client().withRole(Directory.ROLE_FRONTEND).ref(Directory.FRONTEND_SERVICE_NAME) ! CreateProduct(id, label, description, imageUrl, price)
  }

  def updateProduct(id:String, label:String, description:String, imageUrl:String, price: Double) = {
    Client().withRole(Directory.ROLE_FRONTEND).ref(Directory.FRONTEND_SERVICE_NAME) ! UpdateProduct(id, label, description, imageUrl, price)
  }

  def updatePrice(id: String, newPrice: Double) = {
    Client().withRole(Directory.ROLE_FRONTEND).ref(Directory.FRONTEND_SERVICE_NAME) ! ChangeProductPrice(id, newPrice)
  }

}
