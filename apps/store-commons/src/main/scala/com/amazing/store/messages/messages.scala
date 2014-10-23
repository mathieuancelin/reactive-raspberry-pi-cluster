package com.amazing.store.messages

import com.amazing.store.models.{Order, User}

case class GetUser(login: String)
case class GetUserResponse(user: Option[User])

case class GetNumberOfItemsInCart(login: String)
case class GetNumberOfItemsInCartResponse(count: Int)

case class CreateProduct(id:String, label:String, description:String, imageUrl:String, price: Double)
case class UpdateProduct(id:String, label:String, description:String, imageUrl:String, price: Double)
case class ChangeProductPrice(productId: String, newUnitPrice: Double)

case class DeleteProduct(productId: String)

case class AddProductToCurrentCart(login: String, productId: String)
case class AddProductToCurrentCartResponse(login: String, productId: String)

case object GetAllOrdersRequest
case class GetAllOrdersResponse(orders: List[Order])

object BackendService {
  case class ListProducts(nb: Int)
  case class ListProductsPrices(ids: List[String])
  case class NewOrder(order: Order)
}
