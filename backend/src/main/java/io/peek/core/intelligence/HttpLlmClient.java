package io.peek.core.intelligence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** TypeSafe System One wire adapter; provider decisions never become operational facts. */
@Component
@EnableConfigurationProperties(LlmProperties.class)
public class HttpLlmClient implements LlmClient {
    private static final int MAX_RESPONSE_BYTES = 32_768;
    private final LlmProperties properties;
    private final ObjectMapper json;
    private final JevResponseValidator validator;

    public HttpLlmClient(LlmProperties properties, ObjectMapper json, JevResponseValidator validator) {
        this.properties = properties; this.validator = validator;
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override public String interpret(JevContract.Input input) {
        if (!properties.enabled() || !properties.configured()) throw new Failure(FailureReason.API_FAILURE);
        validator.validateInput(input);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            String payload = json.writeValueAsString(TypeSafeEvaluation.request(properties.model(), input));
            var builder = HttpRequest.newBuilder(URI.create(properties.endpoint())).timeout(properties.timeout())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            if (!properties.apiKey().isBlank()) builder.header("Authorization", "Bearer " + properties.apiKey());
            try (var client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER).build()) {
                pending = client.sendAsync(builder.build(), info -> new LimitedBody());
                HttpResponse<byte[]> response;
                try { response = pending.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS); }
                catch (TimeoutException | InterruptedException error) {
                    // Cancel before HttpClient.close(), including a stalled response body.
                    pending.cancel(true); throw error;
                }
                if (response.statusCode() == 408 || response.statusCode() == 504) throw new Failure(FailureReason.TIMEOUT);
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new Failure(FailureReason.API_FAILURE);
                return json.writeValueAsString(TypeSafeEvaluation.output(json.readTree(response.body()), input));
            }
        } catch (TimeoutException error) {
            if (pending != null) pending.cancel(true);
            throw new Failure(FailureReason.TIMEOUT);
        } catch (InterruptedException error) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt(); throw new Failure(FailureReason.API_FAILURE);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Failure failure) throw failure;
            if (error.getCause() instanceof java.net.http.HttpTimeoutException) throw new Failure(FailureReason.TIMEOUT);
            throw new Failure(FailureReason.API_FAILURE);
        } catch (IOException error) { throw new Failure(FailureReason.INVALID_RESPONSE); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new Failure(FailureReason.INVALID_RESPONSE)); return;
                }
                var chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
