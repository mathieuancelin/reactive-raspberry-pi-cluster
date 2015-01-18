package com.amazing.store.services

import com.amazing.store.tools.Reference
import com.codahale.metrics.MetricRegistry
import com.distributedstuff.services.api.{ServicesApi, Service, Services}

object ServiceRegistry {

  val registry = Reference.empty[ServicesApi]

  def init(name: String, services: Seq[Service]):Unit = {
    registry.set(Services(name= name).startFromConfig())
    services.map(registry().registerService)
  }
}
