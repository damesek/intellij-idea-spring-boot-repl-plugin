package hu.baader.repl.protocol;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

/** Shared by the IDE HTTP panel and the generated Java workbook helper. */
public final class BoundedBodyHandler {
    private BoundedBodyHandler() {}
    public static HttpResponse.BodyHandler<String> handler(int limit) { return info -> new Subscriber(limit); }
    public static final class Subscriber implements HttpResponse.BodySubscriber<String> {
        private final int limit;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public Subscriber(int limit) { if (limit < 0) throw new IllegalArgumentException("Negative body limit"); this.limit = limit; }
        @Override public CompletionStage<String> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) { incoming.cancel(); return; }
            subscription = incoming;
            result.whenComplete((value, failure) -> { if (failure != null) incoming.cancel(); });
            incoming.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : items) {
                if (buffer.remaining() > limit - bytes.size()) {
                    result.completeExceptionally(new IllegalStateException("HTTP response exceeds the configured body limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
    }
}
