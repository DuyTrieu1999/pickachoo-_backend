package edu.cutie.lightbackend.helper

import io.vertx.ext.web.Route
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.experimental.launch

fun Route.coroutineHandler(fn : suspend (RoutingContext) -> Unit) {
  handler { ctx ->
    launch(ctx.vertx().dispatcher()) {
      try {
        fn(ctx)
      } catch(e: Exception) {
        ctx.fail(e)
      }
    }
  }
}
