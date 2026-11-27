package org.citriage.github;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Applies byte and time limits to full responses, including bodies that stall after headers arrive. */
final class JdkHttpTransport implements GitHubRestClient.Transport {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;

    JdkHttpTransport() throws IOException {
        try {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        } catch (UncheckedIOException | SecurityException exception) {
            throw new IOException("Cannot initialize the GitHub HTTP client. Check local network permissions.", exception);
        }
    }

    @Override
    public GitHubRestClient.Reply get(URI uri, Map<String, String> headers, int limit)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
        headers.forEach(request::header);
        // Redirect and error bodies are not needed except to distinguish a GitHub secondary rate limit.
        var response = client.sendAsync(request.build(), info -> {
            if (info.statusCode() == 403) {
                return new LimitedBody(64 * 1024);
            }
            return info.statusCode() >= 300 ? BodySubscribers.replacing(new byte[0]) : new LimitedBody(limit);
        });
        try {
            HttpResponse<byte[]> result = response.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return new GitHubRestClient.Reply(result.statusCode(), result.headers(), result.body());
        } catch (TimeoutException exception) {
            response.cancel(true);
            throw new HttpTimeoutException("Response deadline exceeded.");
        } catch (InterruptedException exception) {
            response.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof IOException failure) {
                throw failure;
            }
            throw new IOException("HTTP request failed.", exception);
        }
    }

    static final class BodyTooLarge extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class LimitedBody implements BodySubscriber<byte[]> {
        private final BodySubscriber<byte[]> delegate = BodySubscribers.ofByteArray();
        private final int limit;
        private Flow.Subscription subscription;
        private long size;

        private LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription upstream) {
            subscription = upstream;
            delegate.onSubscribe(upstream);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                size += buffer.remaining();
            }
            if (size > limit) {
                delegate.onError(new BodyTooLarge());
                subscription.cancel();
            } else {
                delegate.onNext(buffers);
            }
        }

        @Override
        public void onError(Throwable failure) {
            delegate.onError(failure);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
