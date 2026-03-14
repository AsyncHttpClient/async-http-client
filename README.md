# Async Http Client

[![Build](https://github.com/AsyncHttpClient/async-http-client/actions/workflows/builds.yml/badge.svg)](https://github.com/AsyncHttpClient/async-http-client/actions/workflows/builds.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.asynchttpclient/async-http-client)](https://central.sonatype.com/artifact/org.asynchttpclient/async-http-client)
[![License](https://img.shields.io/github/license/AsyncHttpClient/async-http-client)](https://www.apache.org/licenses/LICENSE-2.0)

AsyncHttpClient (AHC) is a high-performance, asynchronous HTTP client for Java
built on top of [Netty](https://github.com/netty/netty).
It supports HTTP/1.1, HTTP/2, and WebSocket protocols.

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [HTTP Requests](#http-requests)
- [Handling Responses](#handling-responses)
- [HTTP/2](#http2)
- [WebSocket](#websocket)
- [Authentication](#authentication)
- [Proxy Support](#proxy-support)
- [Community](#community)
- [License](#license)

## Features

- **HTTP/2 with multiplexing** — enabled by default over TLS via ALPN,
  with connection multiplexing and GOAWAY handling
- **HTTP/1.1 and HTTP/1.0** — connection pooling and keep-alive
- **WebSocket** — text, binary, and ping/pong frame support
- **Asynchronous API** — non-blocking I/O with `ListenableFuture`
  and `CompletableFuture`
- **Compression** — automatic gzip, deflate, Brotli, and Zstd decompression
- **Authentication** — Basic, Digest, NTLM, and SPNEGO/Kerberos
- **Proxy** — HTTP, SOCKS4, and SOCKS5 with CONNECT tunneling
- **Native transports** — optional Epoll, KQueue, and io_uring
- **Request/response filters** — intercept and transform at each stage
- **Cookie management** — RFC 6265-compliant cookie store
- **Multipart uploads** — file, byte array, input stream, and string parts
- **Resumable downloads** — built-in `ResumableIOExceptionFilter`

## Requirements

Java 11+

## Installation

**Maven:**

```xml
<dependency>
    <groupId>org.asynchttpclient</groupId>
    <artifactId>async-http-client</artifactId>
    <version>3.0.7</version>
</dependency>
```

**Gradle:**

```groovy
implementation 'org.asynchttpclient:async-http-client:3.0.7'
```

<details>
<summary><b>Optional: Native Transport</b></summary>

For lower-latency I/O on Linux, add a native transport dependency:

```xml
<!-- Epoll (Linux) -->
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-transport-native-epoll</artifactId>
    <classifier>linux-x86_64</classifier>
</dependency>

<!-- io_uring (Linux) -->
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-transport-native-io_uring</artifactId>
    <classifier>linux-x86_64</classifier>
</dependency>
```

Then enable in config:

```java
AsyncHttpClient client = asyncHttpClient(config().setUseNativeTransport(true));
```

</details>

<details>
<summary><b>Optional: Brotli / Zstd Compression</b></summary>

```xml
<dependency>
    <groupId>com.aayushatharva.brotli4j</groupId>
    <artifactId>brotli4j</artifactId>
    <version>1.18.0</version>
</dependency>

<dependency>
    <groupId>com.github.luben</groupId>
    <artifactId>zstd-jni</artifactId>
    <version>1.5.7-2</version>
</dependency>
```

</details>

## Quick Start

Import the DSL helpers:

```java
import static org.asynchttpclient.Dsl.*;
```

Create a client, execute a request, and read the response:

```java
try (AsyncHttpClient client = asyncHttpClient()) {
    // Asynchronous
    client.prepareGet("https://www.example.com/")
        .execute()
        .toCompletableFuture()
        .thenApply(Response::getResponseBody)
        .thenAccept(System.out::println)
        .join();

    // Synchronous (blocking)
    Response response = client.prepareGet("https://www.example.com/")
        .execute()
        .get();
}
```

> **Note:** `AsyncHttpClient` instances are long-lived, shared resources.
> Always close them when done. Creating a new client per request will degrade
> performance due to repeated thread pool and connection pool creation.

## Configuration

Use `config()` to build an `AsyncHttpClientConfig`:

```java
AsyncHttpClient client = asyncHttpClient(config()
    .setConnectTimeout(Duration.ofSeconds(5))
    .setRequestTimeout(Duration.ofSeconds(30))
    .setMaxConnections(500)
    .setMaxConnectionsPerHost(100)
    .setFollowRedirect(true)
    .setMaxRedirects(5)
    .setCompressionEnforced(true));
```

## HTTP Requests

### Sending Requests

**Bound** — build directly from the client:

```java
Response response = client
    .prepareGet("https://api.example.com/users")
    .addHeader("Accept", "application/json")
    .addQueryParam("page", "1")
    .execute()
    .get();
```

**Unbound** — build standalone via DSL, then execute:

```java
Request request = get("https://api.example.com/users")
    .addHeader("Accept", "application/json")
    .addQueryParam("page", "1")
    .build();

Response response = client.executeRequest(request).get();
```

Methods: `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`, `TRACE`.

### Request Bodies

Use `setBody` to attach a body. Supported types:

| Type | Description |
|---|---|
| `String` | Text content |
| `byte[]` | Raw bytes |
| `ByteBuffer` | NIO buffer |
| `InputStream` | Streaming input |
| `File` | File content |
| `Publisher<ByteBuf>` | Reactive stream |
| `BodyGenerator` | Custom body generation |

```java
Response response = client
    .preparePost("https://api.example.com/data")
    .setHeader("Content-Type", "application/json")
    .setBody("{\"name\": \"value\"}")
    .execute()
    .get();
```

For streaming bodies, see `FeedableBodyGenerator` which lets you push chunks
asynchronously.

### Multipart Uploads

```java
Response response = client
    .preparePost("https://api.example.com/upload")
    .addBodyPart(new FilePart("file", new File("report.pdf"), "application/pdf"))
    .addBodyPart(new StringPart("description", "Monthly report"))
    .execute()
    .get();
```

Part types: `FilePart`, `ByteArrayPart`, `InputStreamPart`, `StringPart`.

## Handling Responses

### Blocking

```java
Response response = client.prepareGet("https://www.example.com/").execute().get();
```

> Useful for debugging, but defeats the purpose of an async client in production.

### ListenableFuture

`execute()` returns a `ListenableFuture` that supports completion listeners:

```java
ListenableFuture<Response> future = client
    .prepareGet("https://www.example.com/")
    .execute();

future.addListener(() -> {
    Response response = future.get();
    System.out.println(response.getStatusCode());
}, executor);
```

> If `executor` is `null`, the callback runs on the Netty I/O thread.
> **Never block** inside I/O thread callbacks.

### CompletableFuture

```java
client.prepareGet("https://www.example.com/")
    .execute()
    .toCompletableFuture()
    .thenApply(Response::getResponseBody)
    .thenAccept(System.out::println)
    .join();
```

### AsyncCompletionHandler

For most async use cases, extend `AsyncCompletionHandler` — it buffers the
full response and gives you a single `onCompleted(Response)` callback:

```java
client.prepareGet("https://www.example.com/")
    .execute(new AsyncCompletionHandler<String>() {
        @Override
        public String onCompleted(Response response) {
            return response.getResponseBody();
        }
    });
```

### AsyncHandler

For fine-grained control, implement `AsyncHandler` directly. This lets you
inspect status, headers, and body chunks as they arrive and abort early:

```java
Future<Integer> future = client
    .prepareGet("https://www.example.com/")
    .execute(new AsyncHandler<>() {
        private int status;

        @Override
        public State onStatusReceived(HttpResponseStatus s) {
            status = s.getStatusCode();
            return State.CONTINUE;
        }

        @Override
        public State onHeadersReceived(HttpHeaders headers) {
            return State.CONTINUE;
        }

        @Override
        public State onBodyPartReceived(HttpResponseBodyPart part) {
            return State.ABORT; // stop early — we only needed the status
        }

        @Override
        public Integer onCompleted() {
            return status;
        }

        @Override
        public void onThrowable(Throwable t) {
            t.printStackTrace();
        }
    });
```

## HTTP/2

HTTP/2 is **enabled by default** for HTTPS connections via ALPN negotiation.
The client uses HTTP/2 when the server supports it and falls back to HTTP/1.1
otherwise. No additional configuration is required.

- **Connection multiplexing** — concurrent streams over a single TCP connection
- **GOAWAY handling** — graceful connection draining on server shutdown
- **PING keepalive** — configurable ping frames to keep connections alive

### HTTP/2 Configuration

```java
AsyncHttpClient client = asyncHttpClient(config()
    .setHttp2MaxConcurrentStreams(100)
    .setHttp2InitialWindowSize(65_535)
    .setHttp2MaxFrameSize(16_384)
    .setHttp2MaxHeaderListSize(8_192)
    .setHttp2PingInterval(Duration.ofSeconds(30))  // keepalive pings
    .setHttp2CleartextEnabled(true));               // h2c prior knowledge
```

To force HTTP/1.1, disable HTTP/2:

```java
AsyncHttpClient client = asyncHttpClient(config().setHttp2Enabled(false));
```

## WebSocket

```java
WebSocket ws = client
    .prepareGet("wss://echo.example.com/")
    .execute(new WebSocketUpgradeHandler.Builder()
        .addWebSocketListener(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws) {
                ws.sendTextFrame("Hello!");
            }

            @Override
            public void onTextFrame(String payload, boolean finalFragment, int rsv) {
                System.out.println(payload);
            }

            @Override
            public void onClose(WebSocket ws, int code, String reason) {}

            @Override
            public void onError(Throwable t) { t.printStackTrace(); }
        })
        .build())
    .get();
```

## Authentication

```java
// Client-wide Basic auth
AsyncHttpClient client = asyncHttpClient(config()
    .setRealm(basicAuthRealm("user", "password")));

// Per-request Digest auth
Response response = client
    .prepareGet("https://api.example.com/protected")
    .setRealm(digestAuthRealm("user", "password").build())
    .execute()
    .get();
```

Supported schemes: **Basic**, **Digest**, **NTLM**, **SPNEGO/Kerberos**.

## Proxy Support

```java
// HTTP proxy
AsyncHttpClient client = asyncHttpClient(config()
    .setProxyServer(proxyServer("proxy.example.com", 8080)));

// Authenticated proxy
AsyncHttpClient client = asyncHttpClient(config()
    .setProxyServer(proxyServer("proxy.example.com", 8080)
        .setRealm(basicAuthRealm("proxyUser", "proxyPassword"))));
```

SOCKS4 and SOCKS5 proxies are also supported.

## Community

- [GitHub Discussions](https://github.com/AsyncHttpClient/async-http-client/discussions) — questions, ideas, and general discussion
- [Issue Tracker](https://github.com/AsyncHttpClient/async-http-client/issues) — bug reports and feature requests
- [Examples](https://github.com/AsyncHttpClient/async-http-client/tree/main/example/src/main/java/org/asynchttpclient/example) — sample projects

## License

[Apache License 2.0](LICENSE.txt)
