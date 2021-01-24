# [WIP] AsyncHttpClient Technical Overview

#### Disclaimer

This document is a work in progress. 

## Motivation

While heavily used (~2.3M downloads across the project in December 2020 alone), AsyncHttpClient does not - at this point in time - have a single guiding document that explains how it works internally. As a maintainer fresh on the scene it was unclear to me ([@TomGranot](https://github.com/TomGranot)) exactly how all the pieces fit together.

As part of the attempt to educate myself, I figured it would be a good idea to write a technical overview of the project.  This document  provides an in-depth walkthtough of the library, allowing new potential contributors to "hop on" the coding train as fast as possible. 

Note that this library *is not small*. I expect that in addition to offering a better understanding as to how each piece *works*, writing this document will also allow me to understand which pieces *do not work* as well as expected, and direct me towards things that need a little bit of love.

PRs are open for anyone who wants to help out. For now - let the fun begin. :)

**Note: This guide was written against AsyncHttpClient 2.12.2**.

## The flow of a request 

### Introduction

AsyncHTTPClient was originally designed to be an *Asynchronous* HTTP Client. That meant that it needs to have some underlying mechanism of dealing with response data that arrives **asynchronously**. To make that part easier, the creator of the library ([@jfarcand](https://github.com/jfarcand)) built it on top of [Netty](https://netty.io/), which is (by their own [definition](https://netty.io/#content:~:text=Netty%20is%20a%20NIO%20client%20server,as%20TCP%20and%20UDP%20socket%20server.)) "a framework that enables quick and easy development of network applications".  

That this article does not purport to be a Netty user guide. If you're interested in all Netty has to offer, you should check out the [official user guide](https://netty.io/wiki/user-guide-for-4.x.html). This article is, instead, more of a discussion of implementing Netty *in the wild* - an overview of what a client library built on top of Netty actually looks like in practice. 

### The code in full

The best way to explore what the client actually does is, of course, by following the path a request takes.

Consider the following bit of code, [taken verbatim from one of the simplest tests](https://github.com/AsyncHttpClient/async-http-client/blob/2b12d0ba819e05153fa265b4da7ca900651fd5b3/client/src/test/java/org/asynchttpclient/BasicHttpTest.java#L81-L91) in the library:

```java
  @Test
  public void getRootUrl() throws Throwable {
    withClient().run(client ->
      withServer(server).run(server -> {
        String url = server.getHttpUrl();
        server.enqueueOk();

        Response response = client.executeRequest(get(url), new AsyncCompletionHandlerAdapter()).get(TIMEOUT, SECONDS);
        assertEquals(response.getUri().toUrl(), url);
      }));
  }
```

The functional-style code in lines 3-6 takes care of spinnng up a server to run the test against, and creating an instance of `AsyncHttpClient` (called `client`).  If you were to drill deeper into the code, you'd notice that the instantiation of `client` can be simplified to (converted from functional to procedural for the sake of the explanation):

```java
DefaultAsyncHttpClientConfig config = new DefaultAsyncHttpClientConfig.Builder().build.()setMaxRedirects(0);
AsyncHttpClient client = new DefaultAsyncHttpClient(config);
```

The code in line 8 executes a `GET` request to the URL of the server that was previously spun up, while line 9 is the actual assertion part of our test. Once the request is completed, the final `Response` object is returned.

The intersting bits, of course, happen between the lines - and the best way to start the discussion is to consider what happens under the hood when a new client is instantiated.

### Creating a new AsyncHTTPClient - Configuration

AHC was designed to be *heavily configurable*. There are many, many different knobs you can turn in order to get it to behave _just right_. [`DefaultAsyncHttpClientConfig`](https://github.com/AsyncHttpClient/async-http-client/blob/d4f1e5835b81a5e813033ba2a64a07b020c70007/client/src/main/java/org/asynchttpclient/DefaultAsyncHttpClientConfig.java) is utility class that pulls a set of [hard-coded, sane defaults](https://github.com/AsyncHttpClient/async-http-client/blob/d4f1e5835b81a5e813033ba2a64a07b020c70007/client/src/main/resources/org/asynchttpclient/config/ahc-default.properties) to prevent you from having to deal with configurations, if you don't really want to. 

A keen observer will note that `DefaultAsyncHttpClient` can not be instantiated in and of itself, and follows the [Builder Pattern](https://dzone.com/articles/design-patterns-the-builder-pattern) to allow for method chaining. This is useful - many times you want to change a few parameters in a request (`followRedirect`, `requestTimeout`, etc... ) but still rely on the rest of the default configuration properties. The `setMaxRedicrects(0)` from the initialization code above is an example of that exact behaviour, useful in the context of the test - we don't want any redirects to happen following the `GET` request, and so we turn a knob to ensure none do.

### Creating a new AsyncHTTPClient - Client Instantiation

Once we've decided on a proper configuration, it's time to create a client - the [`DefaultAsyncHttpClient`](https://github.com/AsyncHttpClient/async-http-client/blob/a44aac86616f4e8ffe6977dfef0f0aa460e79d07/client/src/main/java/org/asynchttpclient/DefaultAsyncHttpClient.java), to be precise. Let's take a look at it:

```java
  public DefaultAsyncHttpClient(AsyncHttpClientConfig config) {

    this.config = config;
    this.noRequestFilters = config.getRequestFilters().isEmpty();
    allowStopNettyTimer = config.getNettyTimer() == null;
    nettyTimer = allowStopNettyTimer ? newNettyTimer(config) : config.getNettyTimer();

    channelManager = new ChannelManager(config, nettyTimer);
    requestSender = new NettyRequestSender(config, channelManager, nettyTimer, new AsyncHttpClientState(closed));
    channelManager.configureBootstraps(requestSender);

    CookieStore cookieStore = config.getCookieStore();
    if (cookieStore != null) {
      int cookieStoreCount = config.getCookieStore().incrementAndGet();
      if (
        allowStopNettyTimer // timer is not shared
        || cookieStoreCount == 1 // this is the first AHC instance for the shared (user-provided) timer
      ) {
        nettyTimer.newTimeout(new CookieEvictionTask(config.expiredCookieEvictionDelay(), cookieStore),
          config.expiredCookieEvictionDelay(), TimeUnit.MILLISECONDS);
      }
    }
  }
```

 The constructor actually reveals a lot of the moving parts of AHC, and is worth a proper walkthrough:

1. `RequestFilters` are a way to perform some form of computation **before sending a request to a server**. You can read more about request filters [here](#request-filters), but a simple example is the [ThrottleRequestFilter](https://github.com/AsyncHttpClient/async-http-client/blob/758dcf214bf0ec08142ba234a3967d98a3dc60ef/client/src/main/java/org/asynchttpclient/filter/ThrottleRequestFilter.java), that throttles requests by waiting for a response to arrive before executing the next request in line. 
   1. Note that there is another set of filters, `ResponseFilters`, that can perform computations before processing the first byte of the response. You can read more about them [here](#response-filters).
2. `NettyTimer` is actually not a timer, but a *task executor* that waits an arbitrary amount of time before performing the next task. In the case of the code above, it is used for 
   evicting cookies after they expire - but it has many different use cases (request timeouts being a prime example).
3. `ChannelManager` requires a [section of its own](#channelmanager), but the bottom line is that there's a lot of boilerplate work to do with Netty channels in the context of an HTTP client. For any given request there's a variable number of channel operations you would have to take, and there's a lot of value in correctly re-using and handling existing channels instead of opening new ones. `ChannelManager` is AHC's way of encapsulating at least some of that functionality (for example, [connection pooling](https://en.wikipedia.org/wiki/Connection_pool#:~:text=In%20software%20engineering%2C%20a%20connection,executing%20commands%20on%20a%20database.)) in a single place, instead of having it spread out all over.
   1. `ChannelPool`, as it is [implemented in AHC](https://github.com/AsyncHttpClient/async-http-client/blob/758dcf214bf0ec08142ba234a3967d98a3dc60ef/client/src/main/java/org/asynchttpclient/channel/ChannelPool.java#L21), predates the [Netty implementation](https://netty.io/news/2015/05/07/4-0-28-Final.html) introduced in 2015 (see this [AHC user guide entry](https://asynchttpclient.github.io/async-http-client/configuring.html#contentBox:~:text=ConnectionsPoo,-%3C) from 2012 in which `ConnectionPool` is referenced as proof). As the [Netty release mentions](https://netty.io/news/2015/05/07/4-0-28-Final.html#main-content:~:text=Many%20of%20our%20users%20needed%20to,used%20Netty%20to%20writing%20a%20client.), connection pooling in the world of Netty-based clients is a valuable feature to have, one that [Jean-Francois](https://github.com/jfarcand) implemented himself instead of waiting for Netty to do so. This might comfuse anyone coming to the code a bit later, and I have yet to explore the tradeoffs of stripping away the current implementation and swapping it for the upstream one. See [this issue](https://github.com/AsyncHttpClient/async-http-client/issues/1766) for current progress.
   2. [`ChannelGroup`](https://netty.io/4.0/api/io/netty/channel/group/ChannelGroup.html) (not to be confused with `ChannelPool`) is a Netty structure designed to work with Netty `Channel`s in bulk. In our context, it is mainly used as a set to add and remove channels from as we created them throguhout the lifetime of a connection.
4. `NettyRequestSender` does the all the heavy lifting required for sending the HTTP request - creating the required `Request` and `Response` objects,  making sure  `CONNECT` requests are sent and dealing with proxy servers (in the case of [HTTPS connections](https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/CONNECT)),  dispatching DNS hostname resolution requests and more. Specifically, it gets an open channel (if one already exists in the pool) or requests the creation of one specifically for our request (as is the case with our simple test here). When finished with all the work, it will send back a  `ListenableFuture`,  which in turn is an extension of `Future`
   1. AHC's [`ListenableFuture`](https://github.com/AsyncHttpClient/async-http-client/blob/d47c56e7ee80b76a4cffd4770237239cfea0ffd6/client/src/main/java/org/asynchttpclient/ListenableFuture.java#L40) is an example of a *very* common abstraction that exists in many different Java projects (Google's [Guava](https://github.com/google/guava) [has one](https://github.com/google/guava/blob/master/futures/listenablefuture1/src/com/google/common/util/concurrent/ListenableFuture.java), and so does [Spring](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/util/concurrent/ListenableFuture.html)). Traditionally, it's an extension of a normal Java `Future` that allows for the addition of "Listeners" - pieces of code that get executed once the computation (the one blocking the `Future` from completing) is finished.
   2. Note the invocation of `configureBootstraps` here. `Bootstrap`s are a Netty concept that make it easy to set up `Channel`s - we'll talk about them a bit later.
5. `CookieStore` is, well, a container for cookies. In this context, it is used to handle the task of cookie eviction (removing cookies whose expiry date has passed). This is an example for one of the many, many features AHC supports out of the box and might not be evident upon first observation.

Once the client has been properly configured, it's time to actually execute the request.

### Executing a request - Before execution

Take a look at like 8 from the code above again:

```java
Response response = client.executeRequest(get(url), new AsyncCompletionHandlerAdapter()).get(TIMEOUT, SECONDS);
```

Remember that what we have in front of us is an instance of `AsyncHttpClient` called `client` that is configured with an `AsyncHttpClientConfig`, and more specifically an instance of `DefaultAsyncHttpClient` that is configured with `DefaultAsyncHttpClientConfig`. 

The `executeRequest` method is passed two arguments, and returns a `ListenableFuture`. The `Response` created by executing the `get` method on the `ListenableFuture` is the end of the line in our case here, since this test is very simple - there's no response body to parse to assert the test succeeded. The only that's required for the correct operation of the code is for the `Response` to come back with the correct URL. 

Let's turn our eyes to the two arguments passed to `executeRequest`, then, since they are the key parts here

1. `get(url)` is the functional equivalent of `new RequestBuilder("GET").setUrl(url)`. `RequestBuilder` is in charge of scaffolding an instance of [AHC's `Request` object](https://github.com/AsyncHttpClient/async-http-client/blob/c5eff423ebdd0cddd00bc6fcf17682651a151028/client/src/main/java/org/asynchttpclient/Request.java) and providing it with sane defaults - mostly regarding HTTP headers (`RequestBuilder` does for `Request` what `DefaultAsyncHttpClientConfig.Builder()` does for `DefaultAsyncHttpClient`).  
   1. In our case, the `Request` contains no body (it's a simple `GET`), but of course that `POST` is a thing and people send payloads to servers via HTTP.  We'll be talking about `Request` in more detail [here](#working-with-request-bodies), including how to work with request bodies.  
2. To fully understand what `AsyncCompletionHandlerAdapter` is, and why it's such a core piece of everything that goes on here, a bit of Netty background is required.

#### Netty `Channel`s and their associated entities ( `ChannelPipeline`s, `ChannelHandler`s and `ChannelAdapter`s)

Recall that AHC is built on - and thus heavily relies on - [Netty](https://netty.io/) and its networking abstractions. If you want to dive deeper into the framework you **should** read [Netty in Action](https://www.manning.com/books/netty-in-action), but for the sake of our discussion it's enough to settle on clarifying a few basic terms:

1. [`Channel`](https://netty.io/4.1/api/io/netty/channel/Channel.html) is Netty's version of a normal Java [`Socket`](https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html), greatly simplified for easier usage. It encapsulates [all that you can](https://netty.io/4.1/api/io/netty/channel/Channel.html#allclasses_navbar_top:~:text=the%20current%20state%20of%20the%20channel,and%20requests%20associated%20with%20the%20channel.) know and do with a regular `Socket`:
   1.  **State** - Is the socket currently open? Is it currently closed?
   2. **I/O Options** - Can we read from it? Can we write to it?
   3. **Configuration** - What is the receive buffer size? What is the connect timeout?
   4. `ChannelPipleine` - A reference to this `Channel`'s `ChannelPipeline`.
2.  [`ChannelPipeline`](https://netty.io/4.1/api/io/netty/channel/ChannelPipeline.html) - Note that a channel, in and of itself, is **blocking** - that is, any operation that is performed on it blocks any other operations from being performed on it at that point in time. This is contrary to the Asynchronous nature Netty purports to support. To solve the issue, Netty adds a `ChannelPipeline` to every new `Channel` that is initialised. A `ChannelPipeline` is nothing but a container for `ChannelHandlers`. 

#### `AsyncCompletionHandler`

TODO

### Executing a request - During execution

TODO

### Executing a request - After execution

TODO

## Working with Netty channels

### ChannelManager

TODO

## Transforming requests and responses

TODO

### Working with Request Bodies

TODO

### Request Filters

TODO

### Working with Response Bodies

TODO

### Response Filters

TODO

### Handlers

TODO

## Resources

### Netty

* https://seeallhearall.blogspot.com/2012/05/netty-tutorial-part-1-introduction-to.html

### AsyncHttpClient

TODO

### HTTP

TODO

## Footnotes

[^1] Some Netty-related definitions borrow heavily from [here](https://seeallhearall.blogspot.com/2012/05/netty-tutorial-part-1-introduction-to.html).