# Async-http-client and Typesafe Config integration

An `AsyncHttpClientConfig` implementation integrating [Typesafe Config][1] with Async Http Client.
## Download

Download [the latest JAR][2] or grab via [Maven][3]:

```xml
<dependency>
  <groupId>org.asynchttpclient</groupId>
  <artifactId>async-http-client-extras-typesafe-config</artifactId>
  <version>latest.version</version>
</dependency>
```

or [Gradle][3]:

```groovy
compile "org.asynchttpclient:async-http-client-extras-typesafe-config:latest.version"
```

 [1]: https://github.com/lightbend/config
 [2]: https://search.maven.org/remote_content?g=org.asynchttpclient&a=async-http-client-extras-typesafe-config&v=LATEST
 [3]: http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.asynchttpclient%22%20a%3A%22async-http-client-extras-typesafe-config%22
 [snap]: https://oss.sonatype.org/content/repositories/snapshots/

## Example usage

```java
// creating async-http-client with Typesafe config
com.typesafe.config.Config config = ...
AsyncHttpClientTypesafeConfig ahcConfig = new AsyncHttpClientTypesafeConfig(config);
AsyncHttpClient client = new DefaultAsyncHttpClient(ahcConfig);
```
