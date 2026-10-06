package testplugins.web

import xyz.felismp.shoparchive.api.plugin.PluginResponse
import xyz.felismp.shoparchive.api.plugin.ShopPlugin

/** A few routes: plain JSON, path parameters and query, a body echoed as text, a permission check, a custom status, and one that throws. */
class WebPlugin : ShopPlugin() {
    override fun onEnable() {
        val routes = context.routes
        routes.get("/hello") { PluginResponse.ok("""{"hello":"${it.userName}","user":"${it.userId}"}""") }
        routes.get("/items/{id}") { PluginResponse.ok("""{"id":"${it.pathParams["id"]}","path":"${it.path}","q":"${it.query["q"]?.joinToString("+")}"}""") }
        routes.post("/echo") { PluginResponse(201, "${it.method}:${it.body}", "text/plain; charset=utf-8") }
        routes.put("/echo") { PluginResponse(200, "${it.method}:${it.body}", "text/plain") }
        routes.delete("/echo") { PluginResponse(204) }
        routes.get("/perm") { PluginResponse.ok("""{"admin":${it.hasPermission("web.admin")}}""") }
        routes.get("/teapot") { PluginResponse(418, """{"tea":true}""") }
        routes.get("/boom") { error("secret internal detail") }
        routes.get("/") { PluginResponse.ok("""{"root":true}""") }
    }
}
