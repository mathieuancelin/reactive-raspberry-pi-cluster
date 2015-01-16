package com.amazing.store.services

import com.amazing.store.tools.Reference
import com.codahale.metrics.MetricRegistry
import com.distributedstuff.services.api.{ServicesApi, Service, Services}

/**
 * Created by adelegue on 16/01/15.
 */
object ServiceRegistry {

  val registry = Reference.empty[ServicesApi]

  def init(name: String, metrics: MetricRegistry, services: Seq[Service]):Unit = {
    registry.set(Services(name= name, metrics = metrics).startFromConfig())
    services.map(registry().registerService)
  }


}
