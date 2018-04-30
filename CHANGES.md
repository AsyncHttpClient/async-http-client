## From 2.2 to 2.3

* New `isFilterInsecureCipherSuites` config to disable unsecure and weak ciphers filtering performed internally in Netty.

## From 2.1 to 2.2

* New [Typesafe config](https://github.com/lightbend/config) extra module
* new `enableWebSocketCompression` config to enable per-message and per-frame WebSocket compression extension

## From 2.0 to 2.1

* AHC 2.1 targets Netty 4.1.
* `org.asynchttpclient.HttpResponseHeaders` was [dropped](https://github.com/AsyncHttpClient/async-http-client/commit/f4786f3ac7699f8f8664e7c7db0b7097585a0786) in favor of `io.netty.handler.codec.http.HttpHeaders`.
* `org.asynchttpclient.cookie.Cookie` was [dropped](https://github.com/AsyncHttpClient/async-http-client/commit/a6d659ea0cc11fa5131304d8a04a7ba89c7a66af) in favor of `io.netty.handler.codec.http.cookie.Cookie` as AHC's cookie parsers were contributed to Netty.
* AHC now has a RFC6265 `CookieStore` that is enabled by default. Implementation can be changed in `AsyncHttpClientConfig`.
* `AsyncHttpClient` now exposes stats with `getClientStats`.
* `AsyncHandlerExtensions` was [dropped](https://github.com/AsyncHttpClient/async-http-client/commit/1972c9b9984d6d9f9faca6edd4f2159013205aea) in favor of default methods in `AsyncHandler`.
* `WebSocket` and `WebSocketListener` methods were renamed to mention frames
* `AsyncHttpClientConfig` various changes:
  * new `getCookieStore` now lets you configure a CookieStore (enabled by default)
  * new `isAggregateWebSocketFrameFragments` now lets you disable WebSocket fragmented frames aggregation
  * new `isUseLaxCookieEncoder` lets you loosen cookie chars validation
  * `isAcceptAnyCertificate` was dropped, as it didn't do what its name stated
  * new `isUseInsecureTrustManager` lets you use a permissive TrustManager, that would typically let you accept self-signed certificates
  * new `isDisableHttpsEndpointIdentificationAlgorithm` disables setting `HTTPS` algorithm on the SSLEngines, typically disables SNI and HTTPS hostname verification
  * new `isAggregateWebSocketFrameFragments` lets you disable fragmented WebSocket frames aggregation
