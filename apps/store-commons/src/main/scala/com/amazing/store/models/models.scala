package com.amazing.store.models

import java.util.UUID

import org.joda.time.DateTime

case class User(id: String, login: String, name: String, password: String)

case class Order(id: UUID, login: String, lines: List[OrderLine], totalcost: Double, at: DateTime)
case class OrderLine(productId: String, unitPrice: Double, quantity: Int)


