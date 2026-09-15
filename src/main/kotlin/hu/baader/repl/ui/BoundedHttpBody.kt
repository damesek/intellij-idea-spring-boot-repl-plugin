package hu.baader.repl.ui

import hu.baader.repl.protocol.BoundedBodyHandler
import java.net.http.HttpResponse

object BoundedHttpBody {
    fun handler(limit: Int = 1024 * 1024): HttpResponse.BodyHandler<String> = BoundedBodyHandler.handler(limit)
    internal class Subscriber(limit: Int) : HttpResponse.BodySubscriber<String> by BoundedBodyHandler.Subscriber(limit)
}
