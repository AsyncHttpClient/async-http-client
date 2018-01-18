/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.extras.retrofit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.testserver.HttpServer;
import org.asynchttpclient.testserver.HttpTest;
import org.testng.annotations.*;
import retrofit2.HttpException;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;
import rx.schedulers.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.asynchttpclient.extras.retrofit.TestServices.Contributor;
import static org.testng.Assert.*;

/**
 * All tests in this test suite are disabled, because they call functionality of github service that is
 * rate-limited.
 */
@Slf4j
public class AsyncHttpRetrofitIntegrationTest extends HttpTest {
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final String OWNER = "AsyncHttpClient";
  private static final String REPO = "async-http-client";

  private static final AsyncHttpClient httpClient = createHttpClient();
  private static HttpServer server;

  private List<Contributor> expectedContributors;

  private static AsyncHttpClient createHttpClient() {
    val config = new DefaultAsyncHttpClientConfig.Builder()
            .setCompressionEnforced(true)
            .setTcpNoDelay(true)
            .setKeepAlive(true)
            .setPooledConnectionIdleTimeout(120_000)
            .setFollowRedirect(true)
            .setMaxRedirects(5)
            .build();

    return new DefaultAsyncHttpClient(config);
  }

  @BeforeClass
  public static void start() throws Throwable {
    server = new HttpServer();
    server.start();
  }

  @BeforeTest
  void before() {
    this.expectedContributors = generateContributors();
  }

  @AfterSuite
  void cleanup() throws IOException {
    httpClient.close();
  }

  // begin: synchronous execution
  @Test
  public void testSynchronousService_OK() throws Throwable {
    // given
    val service = synchronousSetup();

    // when:
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 200, expectedContributors, "utf-8");

      val contributors = service.contributors(OWNER, REPO).execute().body();
      resultRef.compareAndSet(null, contributors);
    });

    // then
    assertContributors(expectedContributors, resultRef.get());
  }

  @Test
  public void testSynchronousService_OK_WithBadEncoding() throws Throwable {
    // given
    val service = synchronousSetup();

    // when:
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 200, expectedContributors, "us-ascii");

      val contributors = service.contributors(OWNER, REPO).execute().body();
      resultRef.compareAndSet(null, contributors);
    });

    // then
    assertContributorsWithWrongCharset(expectedContributors, resultRef.get());
  }

  @Test
  public void testSynchronousService_FAIL() throws Throwable {
    // given
    val service = synchronousSetup();

    // when:
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 500, expectedContributors, "utf-8");

      val contributors = service.contributors(OWNER, REPO).execute().body();
      resultRef.compareAndSet(null, contributors);
    });

    // then:
    assertNull(resultRef.get());
  }

  @Test
  public void testSynchronousService_NOT_FOUND() throws Throwable {
    // given
    val service = synchronousSetup();

    // when:
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 404, expectedContributors, "utf-8");

      val contributors = service.contributors(OWNER, REPO).execute().body();
      log.info("contributors: {}", contributors);
      resultRef.compareAndSet(null, contributors);
    });

    // then:
    assertNull(resultRef.get());
  }

  private TestServices.GithubSync synchronousSetup() {
    val callFactory = AsyncHttpClientCallFactory.builder().httpClient(httpClient).build();
    val retrofit = createRetrofitBuilder()
            .callFactory(callFactory)
            .build();
    return retrofit.create(TestServices.GithubSync.class);
  }
  // end: synchronous execution

  // begin: rxjava 1.x
  @Test(dataProvider = "testRxJava1Service")
  public void testRxJava1Service_OK(RxJavaCallAdapterFactory rxJavaCallAdapterFactory) throws Throwable {
    // given
    val service = rxjava1Setup(rxJavaCallAdapterFactory);
    val expectedContributors = generateContributors();

    // when
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 200, expectedContributors, "utf-8");

      // execute retrofit request
      val contributors = service.contributors(OWNER, REPO).toBlocking().first();
      resultRef.compareAndSet(null, contributors);
    });

    // then
    assertContributors(expectedContributors, resultRef.get());
  }

  @Test(dataProvider = "testRxJava1Service")
  public void testRxJava1Service_OK_WithBadEncoding(RxJavaCallAdapterFactory rxJavaCallAdapterFactory)
          throws Throwable {
    // given
    val service = rxjava1Setup(rxJavaCallAdapterFactory);
    val expectedContributors = generateContributors();

    // when
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 200, expectedContributors, "us-ascii");

      // execute retrofit request
      val contributors = service.contributors(OWNER, REPO).toBlocking().first();
      resultRef.compareAndSet(null, contributors);
    });

    // then
    assertContributorsWithWrongCharset(expectedContributors, resultRef.get());
  }

  @Test(dataProvider = "testRxJava1Service", expectedExceptions = HttpException.class,
          expectedExceptionsMessageRegExp = ".*HTTP 500 Server Error.*")
  public void testRxJava1Service_HTTP_500(RxJavaCallAdapterFactory rxJavaCallAdapterFactory) throws Throwable {
    // given
    val service = rxjava1Setup(rxJavaCallAdapterFactory);
    val expectedContributors = generateContributors();

    // when
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 500, expectedContributors, "utf-8");

      // execute retrofit request
      val contributors = service.contributors(OWNER, REPO).toBlocking().first();
      resultRef.compareAndSet(null, contributors);
    });
  }

  @Test(dataProvider = "testRxJava1Service",
          expectedExceptions = HttpException.class, expectedExceptionsMessageRegExp = "HTTP 404 Not Found")
  public void testRxJava1Service_NOT_FOUND(RxJavaCallAdapterFactory rxJavaCallAdapterFactory) throws Throwable {
    // given
    val service = rxjava1Setup(rxJavaCallAdapterFactory);
    val expectedContributors = generateContributors();

    // when
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 404, expectedContributors, "utf-8");

      // execute retrofit request
      val contributors = service.contributors(OWNER, REPO).toBlocking().first();
      resultRef.compareAndSet(null, contributors);
    });
  }

  private TestServices.GithubRxJava1 rxjava1Setup(RxJavaCallAdapterFactory rxJavaCallAdapterFactory) {
    val callFactory = AsyncHttpClientCallFactory.builder().httpClient(httpClient).build();
    val retrofit = createRetrofitBuilder()
            .addCallAdapterFactory(rxJavaCallAdapterFactory)
            .callFactory(callFactory)
            .build();
    return retrofit.create(TestServices.GithubRxJava1.class);
  }

  @DataProvider(name = "testRxJava1Service")
  Object[][] testRxJava1Service_DataProvider() {
    return new Object[][]{
            {RxJavaCallAdapterFactory.create()},
            {RxJavaCallAdapterFactory.createAsync()},
            {RxJavaCallAdapterFactory.createWithScheduler(Schedulers.io())},
            {RxJavaCallAdapterFactory.createWithScheduler(Schedulers.computation())},
            {RxJavaCallAdapterFactory.createWithScheduler(Schedulers.trampoline())},
    };
  }
  // end: rxjava 1.x

  // begin: rxjava 2.x
  @Test(dataProvider = "testRxJava2Service")
  public void testRxJava2Service_OK(RxJava2CallAdapterFactory rxJavaCallAdapterFactory) throws Throwable {
    // given
    val service = rxjava2Setup(rxJavaCallAdapterFactory);
    val expectedContributors = generateContributors();

    // when
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 200, expectedContributors, "utf-8");

      // execute retrofit request
      val contributors = service.contributors(OWNER, REPO).blockingGet();
      resultRef.compareAndSet(null, contributors);
    });

    // then
    assertContributors(expectedContributors, resultRef.get());
  }

  @Test(dataProvider = "testRxJava2Service")
  public void testRxJava2Service_OK_WithBadEncoding(RxJava2CallAdapterFactory rxJavaCallAdapterFactory)
          throws Throwable {
    // given
    val service = rxjava2Setup(rxJavaCallAdapterFactory);
    val expectedContributors = generateContributors();

    // when
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 200, expectedContributors, "us-ascii");

      // execute retrofit request
      val contributors = service.contributors(OWNER, REPO).blockingGet();
      resultRef.compareAndSet(null, contributors);
    });

    // then
    assertContributorsWithWrongCharset(expectedContributors, resultRef.get());
  }

  @Test(dataProvider = "testRxJava2Service", expectedExceptions = HttpException.class,
          expectedExceptionsMessageRegExp = ".*HTTP 500 Server Error.*")
  public void testRxJava2Service_HTTP_500(RxJava2CallAdapterFactory rxJavaCallAdapterFactory) throws Throwable {
    // given
    val service = rxjava2Setup(rxJavaCallAdapterFactory);
    val expectedContributors = generateContributors();

    // when
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 500, expectedContributors, "utf-8");

      // execute retrofit request
      val contributors = service.contributors(OWNER, REPO).blockingGet();
      resultRef.compareAndSet(null, contributors);
    });
  }

  @Test(dataProvider = "testRxJava2Service",
          expectedExceptions = HttpException.class, expectedExceptionsMessageRegExp = "HTTP 404 Not Found")
  public void testRxJava2Service_NOT_FOUND(RxJava2CallAdapterFactory rxJavaCallAdapterFactory) throws Throwable {
    // given
    val service = rxjava2Setup(rxJavaCallAdapterFactory);
    val expectedContributors = generateContributors();

    // when
    val resultRef = new AtomicReference<List<Contributor>>();
    withServer(server).run(srv -> {
      configureTestServer(srv, 404, expectedContributors, "utf-8");

      // execute retrofit request
      val contributors = service.contributors(OWNER, REPO).blockingGet();
      resultRef.compareAndSet(null, contributors);
    });
  }

  private TestServices.GithubRxJava2 rxjava2Setup(RxJava2CallAdapterFactory rxJavaCallAdapterFactory) {
    val callFactory = AsyncHttpClientCallFactory.builder().httpClient(httpClient).build();
    val retrofit = createRetrofitBuilder()
            .addCallAdapterFactory(rxJavaCallAdapterFactory)
            .callFactory(callFactory)
            .build();
    return retrofit.create(TestServices.GithubRxJava2.class);
  }

  @DataProvider(name = "testRxJava2Service")
  Object[][] testRxJava2Service_DataProvider() {
    return new Object[][]{
            {RxJava2CallAdapterFactory.create()},
            {RxJava2CallAdapterFactory.createAsync()},
            {RxJava2CallAdapterFactory.createWithScheduler(io.reactivex.schedulers.Schedulers.io())},
            {RxJava2CallAdapterFactory.createWithScheduler(io.reactivex.schedulers.Schedulers.computation())},
            {RxJava2CallAdapterFactory.createWithScheduler(io.reactivex.schedulers.Schedulers.trampoline())},
    };
  }
  // end: rxjava 2.x

  private Retrofit.Builder createRetrofitBuilder() {
    return new Retrofit.Builder()
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(JacksonConverterFactory.create(objectMapper))
            .validateEagerly(true)
            .baseUrl(server.getHttpUrl());
  }

  /**
   * Asserts contributors.
   *
   * @param expected expected list of contributors
   * @param actual   actual retrieved list of contributors.
   */
  private void assertContributors(Collection<Contributor> expected, Collection<Contributor> actual) {
    assertNotNull(actual, "Retrieved contributors should not be null.");
    log.debug("Contributors: {} ->\n  {}", actual.size(), actual);
    assertTrue(expected.size() == actual.size());
    assertEquals(expected, actual);
  }

  private void assertContributorsWithWrongCharset(List<Contributor> expected, List<Contributor> actual) {
    assertNotNull(actual, "Retrieved contributors should not be null.");
    log.debug("Contributors: {} ->\n  {}", actual.size(), actual);
    assertTrue(expected.size() == actual.size());

    // first and second element should have different logins due to problems with decoding utf8 to us-ascii
    assertNotEquals(expected.get(0).getLogin(), actual.get(0).getLogin());
    assertEquals(expected.get(0).getContributions(), actual.get(0).getContributions());

    assertNotEquals(expected.get(1).getLogin(), actual.get(1).getLogin());
    assertEquals(expected.get(1).getContributions(), actual.get(1).getContributions());

    // other elements should be equal
    for (int i = 2; i < expected.size(); i++) {
      assertEquals(expected.get(i), actual.get(i));
    }
  }

  private List<Contributor> generateContributors() {
    val list = new ArrayList<Contributor>();

    list.add(new Contributor(UUID.randomUUID() + ": čćžšđ", 100));
    list.add(new Contributor(UUID.randomUUID() + ": ČĆŽŠĐ", 200));

    IntStream
            .range(0, (int) (Math.random() * 100))
            .forEach(i -> list.add(new Contributor(UUID.randomUUID().toString(), (int) (Math.random() * 500))));

    return list;
  }

  private void configureTestServer(HttpServer server, int status,
                                         Collection<Contributor> contributors,
                                         String charset) {
    server.enqueueResponse(response -> {
      response.setStatus(status);
      if (status == 200) {
        response.setHeader("Content-Type", "application/json; charset=" + charset);
        response.getOutputStream().write(objectMapper.writeValueAsBytes(contributors));
      } else {
        response.setHeader("Content-Type", "text/plain");
        val errorMsg = "This is an " + status + " error";
        response.getOutputStream().write(errorMsg.getBytes());
      }
    });
  }
}
