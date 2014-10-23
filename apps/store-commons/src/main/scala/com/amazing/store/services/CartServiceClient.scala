package com.amazing.store.services

import com.amazing.store.models.Order

import scala.concurrent.{Future, ExecutionContext}
import com.amazing.store.messages._
import com.amazing.store.messages.DeleteProduct
import com.amazing.store.messages.ChangeProductPrice
import com.amazing.store.messages.GetNumberOfItemsInCartResponse
import com.amazing.store.messages.GetNumberOfItemsInCart

object CartServiceClient {
//
//  def addProductToCurrentCart(login: String, productId: String)(implicit ec: ExecutionContext): Future[Option[String]] = {
//    Client().withRole(Directory.ROLE_CART).ref(Directory.CART_SERVICE_NAME)
//      .ask[AddProductToCurrentCartResponse](AddProductToCurrentCart(login, productId)).map {
//      case Some(AddProductToCurrentCartResponse(l, p)) => Some(p)
//      case _ => None
//    } recoverWith {
//      case _ => Future.successful(None)
//    }
//  }
//
//  def getCartCount(login: String)(implicit ec: ExecutionContext): Future[Option[Int]] = {
//    Client().withRole(Directory.ROLE_CART).ref(Directory.CART_SERVICE_NAME)
//        .ask[GetNumberOfItemsInCartResponse](GetNumberOfItemsInCart(login)).map {
//      case Some(GetNumberOfItemsInCartResponse(count)) => Some(count)
//      case _ => None
//    } recoverWith {
//      case _ => Future.successful(None)
//    }
//  }
//
//  def changeProductPrice(productId: String, newPrice: Double): Future[Unit] = {
//    Client().withRole(Directory.ROLE_CART).ref(Directory.CART_SERVICE_NAME).tell(ChangeProductPrice(productId, newPrice))
//    Future.successful(())
//  }
//
//  def deleteProduct(productId: String): Future[Unit] = {
//    Client().withRole(Directory.ROLE_CART).ref(Directory.CART_SERVICE_NAME).tell(DeleteProduct(productId))
//    Future.successful(())
//  }

  def listAllOrders()(implicit ec: ExecutionContext): Future[List[Order]] = {
      Client().withRole(Directory.ROLE_CART).ref(Directory.CART_SERVICE_NAME)
        .ask[GetAllOrdersResponse](GetAllOrdersRequest).map {
        case Some(GetAllOrdersResponse(orders)) => orders
        case _ => List()
      } recoverWith {
        case _ => Future.successful(List())
      }
  }

}
