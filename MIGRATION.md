Migration Guide
---------------

## From 1.8 to 1.9

AsyncHttpClient v1.9 is a preview of v2, so it comes with some breaking changes.

* Target JDK7, drop support for JDK5 and JDK6
* Rename many AsyncHttpClientConfig parameters:
  * `maxTotalConnections` becomes `maxConnections`
  * `maxConnectionPerHost` becomes `maxConnectionsPerHost`
  * `connectionTimeOutInMs` becomes `connectTimeout`
  * `webSocketIdleTimeoutInMs` becomes `webSocketTimeout`
  * `idleConnectionInPoolTimeoutInMs` becomes `pooledConnectionIdleTimeout`
  * `idleConnectionTimeoutInMs` becomes `readTimeout`
  * `requestTimeoutInMs` becomes `requestTimeout`
  * `maxConnectionLifeTimeInMs` becomes `connectionTTL`
  * `redirectEnabled` becomes `followRedirect`
  * `allowPoolingConnection` becomes `allowPoolingConnections`
  * `allowSslConnectionPool` becomes `allowPoolingSslConnections`
  * `connectionTimeout` becomes `connectTimeout`
  * `compressionEnabled` becomes `compressionEnforced`. Default false, so AHC only honors user defined Accept-Encoding.
  * `requestCompressionLevel` was dropped, as it wasn't working
  * `SSLEngineFactory` was moved to Netty config as only Netty honors it
  * `useRawUrl` becomes `disableUrlEncodingForBoundedRequests`, as it's only honored by bound requests
  * `getAllowPoolingConnection` becomes `isAllowPoolingConnection`
* Drop `PerRequestConfig`. `requestTimeOut` and `proxy` can now be directly set on the request.
* Drop `java.net.URI` in favor of own `com.ning.http.client.uri.Uri`. You can use `toJavaNetURI` to convert.
* Drop `Proxy.getURI` in favor of `getUrl`
* Drop deprecated methods: `Request` and `RequestBuilderBase`'s `getReqType` in favor of `getMethod`, `Request.getLength` in favor of `getContentLength`
* Drop deprecated `RealmBuilder.getDomain` in favor of `getNtlmDomain`
* Rename `xxxParameter` (add, set, get...) into `xxxFormParam`
* Rename `xxxQueryParameter` (add, set, get...) into `xxxQueryParam`
* Merge `boolean Request.isRedirectEnabled` and `boolean isRedirectOverrideSet` are merged into `Boolean isRedirectEnabled`
* Remove url parameter from `SignatureCalculator.calculateAndAddSignature`, as it can be fetched on the request parameter
* Rename `com.ning.http.client.websocket` package into `com.ning.http.client.ws`
* WebSocket Listeners now have to implement proper interfaces to be notified or fragment events: `WebSocketByteFragmentListener` and `WebSocketTextFragmentListener`
* Rename WebSocket's `sendTextMessage` into `sendMessage` and `streamText` into `stream`
* Rename NettyAsyncHttpProviderConfig's `handshakeTimeoutInMillis` into `handshakeTimeout`
* Netty provider now creates SslEngines instances with proper hoststring and port.
* Parts, Realm and ProxyServer now take a java.nio.Charset instead of a String charset name
* New AsyncHandlerExtensions methods:
  * `onOpenConnection`,
  * `onConnectionOpen`,
  * `onPoolConnection`,
  * `onConnectionPooled`,
  * `onSendRequest`,
  * `onDnsResolved`,
  * `onSslHandshakeCompleted`
* Rename FluentCaseInsensitiveStringsMap and FluentStringsMap `replace` into `replaceWith` to not conflict with new JDK8 Map methods
* execute no longer throws Exceptions, all of them are notified to the handler/future
