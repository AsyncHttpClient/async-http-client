/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.extras.retrofit;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.RequestBuilder;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * {@link AsyncHttpClient} <a href="http://square.github.io/retrofit/">Retrofit2</a> {@link okhttp3.Call}
 * implementation.
 */
@Value
@Builder(toBuilder = true)
@Slf4j
class AsyncHttpClientCall implements Cloneable, okhttp3.Call {
    /**
     * Default {@link #execute()} timeout in milliseconds (value: <b>{@value}</b>)
     *
     * @see #execute()
     * @see #executeTimeoutMillis
     */
    public static final long DEFAULT_EXECUTE_TIMEOUT_MILLIS = 30_000;

    /**
     * HttpClient instance.
     */
    @NonNull
    AsyncHttpClient httpClient;

    /**
     * {@link #execute()} response timeout in milliseconds.
     */
    @Builder.Default
    long executeTimeoutMillis = DEFAULT_EXECUTE_TIMEOUT_MILLIS;

    /**
     * Retrofit request.
     */
    @NonNull
    @Getter(AccessLevel.NONE)
    Request request;

    /**
     * Request customizer that is being invoked just before async-http-client request is being built.
     */
    Consumer<RequestBuilder> requestCustomizer;

    /**
     * Consumer that gets called just before actual HTTP request is being fired.
     */
    Consumer<Request> onRequestStart;

    /**
     * Consumer that gets called when HTTP request finishes with an exception.
     */
    Consumer<Throwable> onRequestFailure;

    /**
     * Consumer that gets called when HTTP request finishes successfully.
     */
    Consumer<Response> onRequestSuccess;

    /**
     * Tells whether call has been executed.
     *
     * @see #isExecuted()
     * @see #isCanceled()
     */
    private final AtomicReference<CompletableFuture<Response>> futureRef = new AtomicReference<>();

    @Override
    public Request request() {
        return request;
    }

    @Override
    public Response execute() throws IOException {
        try {
            return executeHttpRequest().get(getExecuteTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw toIOException(e.getCause());
        } catch (Exception e) {
            throw toIOException(e);
        }
    }

    @Override
    public void enqueue(Callback responseCallback) {
        executeHttpRequest()
                .thenApply(response -> handleResponse(response, responseCallback))
                .exceptionally(throwable -> handleException(throwable, responseCallback));
    }

    @Override
    public void cancel() {
        val future = futureRef.get();
        if (future != null) {
            if (!future.cancel(true)) {
                log.warn("Cannot cancel future: {}", future);
            }
        }
    }

    @Override
    public boolean isExecuted() {
        val future = futureRef.get();
        return future != null && future.isDone();
    }

    @Override
    public boolean isCanceled() {
        val future = futureRef.get();
        return future != null && future.isCancelled();
    }

    @Override
    public Call clone() {
        return toBuilder().build();
    }

    protected <T> T handleException(Throwable throwable, Callback responseCallback) {
        try {
            if (responseCallback == null) {
                responseCallback.onFailure(this, toIOException(throwable));
            }
        } catch (Exception e) {
            log.error("Exception while executing onFailure() on {}: {}", responseCallback, e.getMessage(), e);
        }
        return null;
    }

    protected Response handleResponse(Response response, Callback responseCallback) {
        try {
            if (responseCallback != null) {
                responseCallback.onResponse(this, response);
            }
        } catch (Exception e) {
            log.error("Exception while executing onResponse() on {}: {}", responseCallback, e.getMessage(), e);
        }
        return response;
    }

    protected CompletableFuture<Response> executeHttpRequest() {
        if (futureRef.get() != null) {
            throwAlreadyExecuted();
        }

        // create future and try to store it into atomic reference
        val future = new CompletableFuture<Response>();
        if (!futureRef.compareAndSet(null, future)) {
            throwAlreadyExecuted();
        }

        // create request
        val asyncHttpClientRequest = createRequest(request());

        // execute the request.
        val me = this;
        runConsumer(this.onRequestStart, this.request);
        getHttpClient().executeRequest(asyncHttpClientRequest, new AsyncCompletionHandler<Response>() {
            @Override
            public void onThrowable(Throwable t) {
                runConsumer(me.onRequestFailure, t);
                future.completeExceptionally(t);
            }

            @Override
            public Response onCompleted(org.asynchttpclient.Response response) throws Exception {
                val okHttpResponse = toOkhttpResponse(response);
                runConsumer(me.onRequestSuccess, okHttpResponse);
                future.complete(okHttpResponse);
                return okHttpResponse;
            }
        });

        return future;
    }

    /**
     * Converts async-http-client response to okhttp response.
     *
     * @param asyncHttpClientResponse async-http-client response
     * @return okhttp response.
     * @throws NullPointerException in case of null arguments
     */
    private Response toOkhttpResponse(org.asynchttpclient.Response asyncHttpClientResponse) {
        // status code
        val rspBuilder = new Response.Builder()
                .request(request())
                .protocol(Protocol.HTTP_1_1)
                .code(asyncHttpClientResponse.getStatusCode())
                .message(asyncHttpClientResponse.getStatusText());

        // headers
        if (asyncHttpClientResponse.hasResponseHeaders()) {
            asyncHttpClientResponse.getHeaders().forEach(e -> rspBuilder.header(e.getKey(), e.getValue()));
        }

        // body
        if (asyncHttpClientResponse.hasResponseBody()) {
            val contentType = MediaType.parse(asyncHttpClientResponse.getContentType());
            val okHttpBody = ResponseBody.create(contentType, asyncHttpClientResponse.getResponseBodyAsBytes());
            rspBuilder.body(okHttpBody);
        }

        return rspBuilder.build();
    }

    protected IOException toIOException(@NonNull Throwable exception) {
        if (exception instanceof IOException) {
            return (IOException) exception;
        } else {
            val message = (exception.getMessage() == null) ? exception.toString() : exception.getMessage();
            return new IOException(message, exception);
        }
    }

    /**
     * Converts retrofit request to async-http-client request.
     *
     * @param request retrofit request
     * @return async-http-client request.
     */
    @SneakyThrows
    protected org.asynchttpclient.Request createRequest(@NonNull Request request) {
        // create async-http-client request builder
        val requestBuilder = new RequestBuilder(request.method());

        // request uri
        requestBuilder.setUrl(request.url().toString());

        // set headers
        val headers = request.headers();
        headers.names().forEach(name -> requestBuilder.setHeader(name, headers.values(name)));

        // set request body
        val body = request.body();
        if (body != null && body.contentLength() > 0) {
            // write body to buffer
            val okioBuffer = new Buffer();
            body.writeTo(okioBuffer);
            requestBuilder.setBody(okioBuffer.readByteArray());
        }

        // customize the request builder (external customizer can change the request url for example)
        runConsumer(this.requestCustomizer, requestBuilder);

        return requestBuilder.build();
    }

    /**
     * Safely runs specified consumer.
     *
     * @param consumer consumer (may be null)
     * @param argument consumer argument
     * @param <T>      consumer type.
     */
    protected <T> void runConsumer(Consumer<T> consumer, T argument) {
        try {
            if (consumer != null) {
                consumer.accept(argument);
            }
        } catch (Exception e) {
            log.error("Exception while running consumer {}: {}", consumer, e.getMessage(), e);
        }
    }

    private void throwAlreadyExecuted() {
        throw new IllegalStateException("This call has already been executed.");
    }
}