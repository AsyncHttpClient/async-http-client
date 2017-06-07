# Async-http-client Retrofit2 Call Adapter

An `okhttp3.Call.Factory` for implementing async-http-client powered [Retrofit][1] type-safe HTTP clients.

## Download

Download [the latest JAR][2] or grab via [Maven][3]:

```xml
<dependency>
  <groupId>org.asynchttpclient</groupId>
  <artifactId>async-http-client-extras-retrofit2</artifactId>
  <version>latest.version</version>
</dependency>
```

or [Gradle][3]:

```groovy
compile "org.asynchttpclient:async-http-client-extras-retrofit2:latest.version"
```

 [1]: http://square.github.io/retrofit/
 [2]: https://search.maven.org/remote_content?g=org.asynchttpclient&a=async-http-client-extras-retrofit2&v=LATEST
 [3]: http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.asynchttpclient%22%20a%3A%22async-http-client-extras-retrofit2%22
 [snap]: https://oss.sonatype.org/content/repositories/snapshots/

## Example usage

```java
// instantiate async-http-client
AsyncHttpClient httpClient = ...

// instantiate async-http-client call factory
Call.Factory callFactory = AsyncHttpClientCallFactory.builder()
    .httpClient(httpClient)                 // required
    .onRequestStart(onRequestStart)         // optional
    .onRequestFailure(onRequestFailure)     // optional
    .onRequestSuccess(onRequestSuccess)     // optional
    .requestCustomizer(requestCustomizer)   // optional
    .build();

// instantiate retrofit
Retrofit retrofit = new Retrofit.Builder()
    .callFactory(callFactory) // use our own call factory
    .addConverterFactory(ScalarsConverterFactory.create())
    .addConverterFactory(JacksonConverterFactory.create())
    // ... add other converter factories
    // .addCallAdapterFactory(RxJavaCallAdapterFactory.createAsync())
    .validateEagerly(true) // highly recommended!!!
    .baseUrl("https://api.github.com/");

// time to instantiate service
GitHub github = retrofit.create(Github.class);

// enjoy your type-safe github service api! :-)
```