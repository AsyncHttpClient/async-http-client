Async Http Client ([@AsyncHttpClient](https://twitter.com/AsyncHttpClient) on twitter) [![Build Status](https://travis-ci.org/AsyncHttpClient/async-http-client.svg?branch=master)](https://travis-ci.org/AsyncHttpClient/async-http-client)
---------------------------------------------------

[Javadoc](http://www.javadoc.io/doc/org.asynchttpclient/async-http-client/)

[Getting](https://jfarcand.wordpress.com/2010/12/21/going-asynchronous-using-asynchttpclient-the-basic/) [started](https://jfarcand.wordpress.com/2011/01/04/going-asynchronous-using-asynchttpclient-the-complex/), and use [WebSockets](http://jfarcand.wordpress.com/2011/12/21/writing-websocket-clients-using-asynchttpclient/)

The Async Http Client library's purpose is to allow Java applications to easily execute HTTP requests and asynchronously process the HTTP responses.
The library also supports the WebSocket Protocol. The Async HTTP Client library is simple to use.

It's built on top of [Netty](https://github.com/netty/netty) and currently requires JDK8.

Latest `version`: [![Maven][mavenImg]][mavenLink]

[mavenImg]: https://img.shields.io/maven-central/v/org.asynchttpclient/async-http-client.svg
[mavenLink]: http://mvnrepository.com/artifact/org.asynchttpclient/async-http-client

## Installation

First, in order to add it to your Maven project, simply download from Maven central or add this dependency:

```xml
<dependency>
	<groupId>org.asynchttpclient</groupId>
	<artifactId>async-http-client</artifactId>
	<version>LATEST_VERSION</version>
</dependency>
```

## Usage

Then in your code you can simply do

```java
import org.asynchttpclient.*;
import java.util.concurrent.Future;

AsyncHttpClient asyncHttpClient = new DefaultAsyncHttpClient();
Future<Response> f = asyncHttpClient.prepareGet("http://www.example.com/").execute();
Response r = f.get();
```

Note that in this case all the content must be read fully in memory, even if you used `getResponseBodyAsStream()` method on returned `Response` object.

You can also accomplish asynchronous (non-blocking) operation without using a Future if you want to receive and process the response in your handler:

```java
import org.asynchttpclient.*;
import java.util.concurrent.Future;

AsyncHttpClient asyncHttpClient = new DefaultAsyncHttpClient();
asyncHttpClient.prepareGet("http://www.example.com/").execute(new AsyncCompletionHandler<Response>(){
    
    @Override
    public Response onCompleted(Response response) throws Exception{
        // Do something with the Response
        // ...
        return response;
    }
    
    @Override
    public void onThrowable(Throwable t){
        // Something wrong happened.
    }
});
```

(this will also fully read `Response` in memory before calling `onCompleted`)

Alternatively you may use continuations (through Java 8 class `CompletableFuture<T>`) to accomplish asynchronous (non-blocking) solution. The equivalent continuation approach to the previous example is:

```java
import static org.asynchttpclient.Dsl.*;

import org.asynchttpclient.*;
import java.util.concurrent.CompletableFuture;

AsyncHttpClient asyncHttpClient = asyncHttpClient();
CompletableFuture<Response> promise = asyncHttpClient
            .prepareGet("http://www.example.com/")
            .execute()
            .toCompletableFuture()
            .exceptionally(t -> { /* Something wrong happened... */  } )
            .thenApply(resp -> { /*  Do something with the Response */ return resp; });
promise.join(); // wait for completion
```

You may get the complete maven project for this simple demo from [org.asynchttpclient.example](https://github.com/AsyncHttpClient/async-http-client/tree/master/example/src/main/java/org/asynchttpclient/example)

You can also mix Future with AsyncHandler to only retrieve part of the asynchronous response

```java
import org.asynchttpclient.*;
import java.util.concurrent.Future;

AsyncHttpClient asyncHttpClient = new DefaultAsyncHttpClient();
Future<Integer> f = asyncHttpClient.prepareGet("http://www.example.com/").execute(
   new AsyncCompletionHandler<Integer>(){
    
    @Override
    public Integer onCompleted(Response response) throws Exception{
        // Do something with the Response
        return response.getStatusCode();
    }
    
    @Override
    public void onThrowable(Throwable t){
        // Something wrong happened.
    }
});

int statusCode = f.get();
```

which is something you want to do for large responses: this way you can process content as soon as it becomes available, piece by piece, without having to buffer it all in memory.

 You have full control on the Response life cycle, so you can decide at any moment to stop processing what the server is sending back:

```java
import static org.asynchttpclient.Dsl.*;

import org.asynchttpclient.*;
import java.util.concurrent.Future;

AsyncHttpClient c = asyncHttpClient();
Future<String> f = c.prepareGet("http://www.example.com/").execute(new AsyncHandler<String>() {
    private ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    @Override
    public STATE onStatusReceived(HttpResponseStatus status) throws Exception {
        int statusCode = status.getStatusCode();
        // The Status have been read
        // If you don't want to read the headers,body or stop processing the response
        if (statusCode >= 500) {
            return STATE.ABORT;
        }
    }

    @Override
    public STATE onHeadersReceived(HttpResponseHeaders h) throws Exception {
        Headers headers = h.getHeaders();
         // The headers have been read
         // If you don't want to read the body, or stop processing the response
         return STATE.ABORT;
    }

    @Override
    public STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
         bytes.write(bodyPart.getBodyPartBytes());
         return STATE.CONTINUE;
    }

    @Override
    public String onCompleted() throws Exception {
         // Will be invoked once the response has been fully read or a ResponseComplete exception
         // has been thrown.
         // NOTE: should probably use Content-Encoding from headers
         return bytes.toString("UTF-8");
    }

    @Override
    public void onThrowable(Throwable t) {
    }
});

String bodyResponse = f.get();
```

## Configuration

Finally, you can also configure the AsyncHttpClient via its AsyncHttpClientConfig object:

```java
AsyncHttpClientConfig cf = new DefaultAsyncHttpClientConfig.Builder()
    .setProxyServer(new ProxyServer.Builder("127.0.0.1", 38080)).build();

AsyncHttpClient c = new DefaultAsyncHttpClient(cf);
```

## WebSocket

Async Http Client also support WebSocket by simply doing:

```java
WebSocket websocket = c.prepareGet(getTargetUrl())
      .execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(
          new WebSocketTextListener() {

          @Override
          public void onMessage(String message) {
          }

          @Override
          public void onOpen(WebSocket websocket) {
              websocket.sendTextMessage("...").sendMessage("...");
          }

          @Override
          public void onClose(WebSocket websocket) {
              latch.countDown();
          }

          @Override
          public void onError(Throwable t) {
          }
      }).build()).get();
```

## User Group

Keep up to date on the library development by joining the Asynchronous HTTP Client discussion group

[Google Group](http://groups.google.com/group/asynchttpclient)

## Contributing

Of course, Pull Requests are welcome.

Here a the few rules we'd like you to respect if you do so:

* Only edit the code related to the suggested change, so DON'T automatically format the classes you've edited.
* Respect the formatting rules:
  * Indent with 4 spaces
* Your PR can contain multiple commits when submitting, but once it's been reviewed, we'll ask you to squash them into a single one
* Regarding licensing:
  * You must be the original author of the code you suggest.
  * You must give the copyright to "the AsyncHttpClient Project"
