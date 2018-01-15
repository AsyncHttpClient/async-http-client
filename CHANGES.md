## From 2.0 to 2.1

* AHC 2.1 targets Netty 4.1.
* `org.asynchttpclient.HttpResponseHeaders` was dropped in favor of `io.netty.handler.codec.http.HttpHeaders`.
* `org.asynchttpclient.cookie.Cookie` was dropped in favor of `io.netty.handler.codec.http.cookie.Cookie` as AHC's cookie parsers were contributed to Netty.
* AHC now has a RFC6265 `CookieStore` that is enabled by default. Implementation can be changed in `AsyncHttpClientConfig`.
* `AsyncHttpClient` now exposes stats with `getClientStats`.
* `AsyncHandlerExtensions` was dropped in favor of default methods in `AsyncHandler`.
* `WebSocket` and `WebSocketListener` methods were renamed to mention frames
* `AsyncHttpClientConfig#isAggregateWebSocketFrameFragments` now lets you disable WebSocket fragmented frames aggregation
