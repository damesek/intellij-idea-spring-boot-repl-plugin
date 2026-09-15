package hu.baader.repl.ui

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Flow

class BoundedHttpBodyTest {
    private class Subscription : Flow.Subscription {
        var cancelled=false
        override fun request(n: Long) {}
        override fun cancel() { cancelled=true }
    }
    @Test fun decodesUtf8AcrossChunks() {
        val subscriber=BoundedHttpBody.Subscriber(100)
        subscriber.onSubscribe(Subscription())
        for (byte in "árvíz 🧪".toByteArray(Charsets.UTF_8)) subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(byte))))
        subscriber.onComplete()
        assertEquals("árvíz 🧪",subscriber.body.toCompletableFuture().get())
    }
    @Test fun cancelsBeforeAcceptingOversizedBody() {
        val subscription=Subscription();val subscriber=BoundedHttpBody.Subscriber(4)
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.wrap(ByteArray(5))))
        assertTrue(subscription.cancelled);assertTrue(subscriber.body.toCompletableFuture().isCompletedExceptionally)
    }
}
