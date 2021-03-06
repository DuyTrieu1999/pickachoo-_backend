package edu.cutie.lightbackend.controller

import edu.cutie.lightbackend.data
import edu.cutie.lightbackend.domain.ProductEntity
import edu.cutie.lightbackend.helper.WithLogger
import edu.cutie.lightbackend.helper.coroutineHandler
import edu.cutie.lightbackend.helper.endWithJson
import edu.cutie.lightbackend.service.elasticSearchClient
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import mbuhot.eskotlin.query.compound.bool
import mbuhot.eskotlin.query.fulltext.multi_match
import mbuhot.eskotlin.query.term.range
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.index.query.MoreLikeThisQueryBuilder
import org.elasticsearch.index.query.MultiMatchQueryBuilder
import org.elasticsearch.search.builder.SearchSourceBuilder


// Being a service is better. But controller works for now.

class SearchController(router: Router, endpoint: String = "/search") : WithLogger {
  init {
    router.get(endpoint).coroutineHandler { search(it) }
    router.get("$endpoint/similar").coroutineHandler { suggest(it) }
  }

  // Sample http://localhost:8080/search?q=Quang&score=1&score=100&difficulty=2&difficulty=100
  private fun search(context: RoutingContext) {
    val q = context.queryParam("q").firstOrNull() ?: ""
    val score = context.queryParam("score").take(2).map(String::toDouble)
    val difficulty = context.queryParam("difficulty").take(2).map(String::toDouble)
    val query = bool {
      must {
        multi_match {
          query = q
          fields = listOf("name^5", "description^2", "department^3", "address") // TODO: tweak this
        }
      }
      filter = listOf(
        range {
          "score" {
            from = score[0]
            to = score[1]
          }
        },
        range {
          "difficulty" {
            from = difficulty[0]
            to = difficulty[1]
          }
        }
      )
    }
    val searchSourceBuilder = SearchSourceBuilder().query(query)
    val searchRequest = SearchRequest("product").source(searchSourceBuilder)
    elasticSearchClient.searchAsync(searchRequest, ActionListener.wrap({ response ->
      val hits = response.hits.hits.map { it.sourceAsMap }
      context.response().endWithJson(hits)
    }, {
      logger.atWarning().withCause(it).log("Query %s failed", context.request().query())
      context.response().endWithJson(HttpResponseStatus.INTERNAL_SERVER_ERROR.reasonPhrase(), HttpResponseStatus.INTERNAL_SERVER_ERROR)
    }))
  }

  private fun suggest(context: RoutingContext) { // TODO: untested
    val id = context.queryParam("id").first()
    val extra = context.queryParam("extra")
    val moreLikeThisQueryBuilder = MoreLikeThisQueryBuilder(
      arrayOf("description", "address", "department"),
      extra.toTypedArray(),
      arrayOf(MoreLikeThisQueryBuilder.Item("product", "_doc", id))
    )
    val searchSourceBuilder = SearchSourceBuilder().query(moreLikeThisQueryBuilder)
    val searchRequest = SearchRequest("product").source(searchSourceBuilder)
    elasticSearchClient.searchAsync(searchRequest, ActionListener.wrap({ response ->
      val hits = response.hits.hits.map { it.sourceAsMap }
      if (hits.isEmpty()) {
        val p = data.select(ProductEntity::class).where(ProductEntity.ID.eq(id.toInt())).get().first()
        val recommendations = data
          .select(ProductEntity::class)
          .where(ProductEntity.DEPARTMENT.like(p.department))
          .orderBy(ProductEntity.SCORE.desc(), ProductEntity.REVIEWS.desc()).get().toList()
        context.response().endWithJson(recommendations)
      } else {
        context.response().endWithJson(hits)
      }
    }, {
      logger.atWarning().withCause(it).log("Query %s failed", context.request().query())
      context.response().endWithJson(HttpResponseStatus.INTERNAL_SERVER_ERROR.reasonPhrase(), HttpResponseStatus.INTERNAL_SERVER_ERROR)
    }))
  }
}
