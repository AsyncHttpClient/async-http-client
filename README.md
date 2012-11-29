Async Http Client
-----------------

Getting started [HTML](http://sonatype.github.com/async-http-client/) [PDF](http://is.gd/kexrN)
                With [WebSockets](http://jfarcand.wordpress.com/2011/12/21/writing-websocket-clients-using-asynchttpclient/)

Async Http Client library purpose is to allow Java applications to easily execute HTTP requests and asynchronously process the HTTP responses. The library also supports the WebSocket Protocol. The Async HTTP Client library is simple to use. First, in order to add it to your Maven project, simply add this dependency:

```xml
         <dependency>
             <groupId>com.ning</groupId>
             <artifactId>async-http-client</artifactId>
             <version>1.7.8</version>
         </dependency>
```

You can also download the artifact

[Maven Search](http://search.maven.org)

Then in your code you can simply do ([Javadoc](http://sonatype.github.com/async-http-client/apidocs/index.html))

```java
    import com.ning.http.client.*;
    import java.util.concurrent.Future;

    AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
    Future<Response> f = asyncHttpClient.prepareGet("http://www.ning.com/").execute();
    Response r = f.get();
```

Note that in this case all the content must be read fully in memory, even if you used `getResponseBodyAsStream()' method on returned `Response` object.

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

Finally, you can also configure the AsyncHttpClient via it's AsyncHttpClientConfig object:

```java

        AsyncHttpClientConfig cf = new AsyncHttpClientConfig.Builder()
            S.setProxyServer(new ProxyServer("127.0.0.1", 38080)).build();
        AsyncHttpClient c = new AsyncHttpClient(cf);
```

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
                        websocket.sendTextMessage("...").sendBinaryMessage("...");
                    }

                    @Override
                    public void onClose(.WebSocket websocket) {
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

Keep up to date on the library development by joining the Asynchronous HTTP Client discussion group

[Google Group](http://groups.google.com/group/asynchttpclient)

or follow us on [Twitter](http://twitter.com/jfarcand)

