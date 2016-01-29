Async Http Client ([@AsyncHttpClient](https://twitter.com/AsyncHttpClient) on twitter) [![Build Status](https://travis-ci.org/AsyncHttpClient/async-http-client.svg?branch=master)](https://travis-ci.org/AsyncHttpClient/async-http-client)
---------------------------------------------------

[Javadoc](http://www.javadoc.io/doc/com.ning/async-http-client/)

[Getting](https://jfarcand.wordpress.com/2010/12/21/going-asynchronous-using-asynchttpclient-the-basic/) [started](https://jfarcand.wordpress.com/2011/01/04/going-asynchronous-using-asynchttpclient-the-complex/), and use [WebSockets](http://jfarcand.wordpress.com/2011/12/21/writing-websocket-clients-using-asynchttpclient/)

Async Http Client library purpose is to allow Java applications to easily execute HTTP requests and asynchronously process the HTTP responses.
The library also supports the WebSocket Protocol. The Async HTTP Client library is simple to use.

## Installation

First, in order to add it to your Maven project, simply add this dependency:

```xml
<dependency>
  <groupId>com.ning</groupId>
  <artifactId>async-http-client</artifactId>
  <version>1.9.33</version>
</dependency>
```

You can also download the artifact

[Maven Search](http://search.maven.org)

AHC is an abstraction layer that can work on top of the bare JDK, Netty and Grizzly.
Note that the JDK implementation is very limited and you should **REALLY** use the other *real* providers.

You then have to add the Netty or Grizzly jars in the classpath.

For Netty:

```xml
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty</artifactId>
    <version>LATEST_NETTY_3_VERSION</version>
</dependency>
```

For Grizzly:

```xml
<dependency>
    <groupId>org.glassfish.grizzly</groupId>
    <artifactId>connection-pool</artifactId>
    <version>LATEST_GRIZZLY_VERSION</version>
</dependency>
<dependency>
    <groupId>org.glassfish.grizzly</groupId>
    <artifactId>grizzly-websockets</artifactId>
    <version>LATEST_GRIZZLY_VERSION</version>
</dependency>
```

Check [migration guide](MIGRATION.md) for migrating from 1.8 to 1.9.

## Usage

Then in your code you can simply do

```java
import com.ning.http.client.*;
import java.util.concurrent.Future;

AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
Future<Response> f = asyncHttpClient.prepareGet("http://www.ning.com/").execute();
Response r = f.get();
```

Note that in this case all the content must be read fully in memory, even if you used `getResponseBodyAsStream()` method on returned `Response` object.

You can also accomplish asynchronous (non-blocking) operation without using a Future if you want to receive and process the response in your handler:

```java
import com.ning.http.client.*;
import java.util.concurrent.Future;

AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
asyncHttpClient.prepareGet("http://www.ning.com/").execute(new AsyncCompletionHandler<Response>(){
    
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

You can also mix Future with AsyncHandler to only retrieve part of the asynchronous response

```java
import com.ning.http.client.*;
import java.util.concurrent.Future;

AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
Future<Integer> f = asyncHttpClient.prepareGet("http://www.ning.com/").execute(
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
import com.ning.http.client.*;
import java.util.concurrent.Future;

AsyncHttpClient c = new AsyncHttpClient();
Future<String> f = c.prepareGet("http://www.ning.com/").execute(new AsyncHandler<String>() {
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
AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder()
    .setProxyServer(new ProxyServer("127.0.0.1", 38080)).build();
AsyncHttpClient c = new AsyncHttpClient(cf);
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

The library uses Java non blocking I/O for supporting asynchronous operations. The default asynchronous provider is build on top of [Netty](http://www.jboss.org/netty), but the library exposes a configurable provider SPI which allows to easily plug in other frameworks like [Grizzly](http://grizzly.java.net)

```java
AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().build();
AsyncHttpClient client = new AsyncHttpClient(new GrizzlyAsyncHttpProvider(config), config);
```

## User Group

Keep up to date on the library development by joining the Asynchronous HTTP Client discussion group

[Google Group](http://groups.google.com/group/asynchttpclient)

## Contributing

Of course, Pull Requests are welcome.

Here a the few rules we'd like you to respect if you do so:

* Only edit the code related to the suggested change, so DON'T automatically format the classes you've edited.
* Respect the formatting rules:
  * Ident with 4 spaces
  * Use a 140 chars line max length
  * Don't use * imports
  * Stick to the org, com, javax, java imports order
* Your PR can contain multiple commits when submitting, but once it's been reviewed, we'll ask you to squash them into a single one
* Regarding licensing:
  * You must be the original author of the code you suggest.
  * If not, you have to prove that the original code was published under Apache License 2 and properly mention original copyrights.
