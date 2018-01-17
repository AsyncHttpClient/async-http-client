/*
 * Copyright (c) 2015 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.reactivestreams;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaders;
import io.reactivex.Flowable;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.asynchttpclient.*;
import org.asynchttpclient.handler.StreamedAsyncHandler;
import org.asynchttpclient.test.TestUtils;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_MD5;
import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES_MD5;
import static org.testng.Assert.assertEquals;

public class ReactiveStreamsTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveStreamsTest.class);
  private Tomcat tomcat;
  private int port1;

  private static Publisher<ByteBuf> createPublisher(final byte[] bytes, final int chunkSize) {
    return Flowable.fromIterable(new ByteBufIterable(bytes, chunkSize));
  }

  private static byte[] getBytes(List<HttpResponseBodyPart> bodyParts) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (HttpResponseBodyPart part : bodyParts) {
      bytes.write(part.getBodyPartBytes());
    }
    return bytes.toByteArray();
  }

  @SuppressWarnings("serial")
  @BeforeClass(alwaysRun = true)
  public void setUpGlobal() throws Exception {

    String path = new File(".").getAbsolutePath() + "/target";

    tomcat = new Tomcat();
    tomcat.setHostname("localhost");
    tomcat.setPort(0);
    tomcat.setBaseDir(path);
    Context ctx = tomcat.addContext("", path);

    Wrapper wrapper = Tomcat.addServlet(ctx, "webdav", new HttpServlet() {

      @Override
      public void service(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
              throws IOException {
        LOGGER.debug("Echo received request {} on path {}", httpRequest,
                httpRequest.getServletContext().getContextPath());

        if (httpRequest.getHeader("X-HEAD") != null) {
          httpResponse.setContentLength(1);
        }

        if (httpRequest.getHeader("X-ISO") != null) {
          httpResponse.setContentType(TestUtils.TEXT_HTML_CONTENT_TYPE_WITH_ISO_8859_1_CHARSET);
        } else {
          httpResponse.setContentType(TestUtils.TEXT_HTML_CONTENT_TYPE_WITH_UTF_8_CHARSET);
        }

        if (httpRequest.getMethod().equalsIgnoreCase("OPTIONS")) {
          httpResponse.addHeader("Allow", "GET,HEAD,POST,OPTIONS,TRACE");
        }

        Enumeration<String> e = httpRequest.getHeaderNames();
        String headerName;
        while (e.hasMoreElements()) {
          headerName = e.nextElement();
          if (headerName.startsWith("LockThread")) {
            final int sleepTime = httpRequest.getIntHeader(headerName);
            try {
              Thread.sleep(sleepTime == -1 ? 40 : sleepTime * 1000);
            } catch (InterruptedException ex) {
              //
            }
          }

          if (headerName.startsWith("X-redirect")) {
            httpResponse.sendRedirect(httpRequest.getHeader("X-redirect"));
            return;
          }
          httpResponse.addHeader("X-" + headerName, httpRequest.getHeader(headerName));
        }

        String pathInfo = httpRequest.getPathInfo();
        if (pathInfo != null)
          httpResponse.addHeader("X-pathInfo", pathInfo);

        String queryString = httpRequest.getQueryString();
        if (queryString != null)
          httpResponse.addHeader("X-queryString", queryString);

        httpResponse.addHeader("X-KEEP-ALIVE", httpRequest.getRemoteAddr() + ":" + httpRequest.getRemotePort());

        Cookie[] cs = httpRequest.getCookies();
        if (cs != null) {
          for (Cookie c : cs) {
            httpResponse.addCookie(c);
          }
        }

        Enumeration<String> i = httpRequest.getParameterNames();
        if (i.hasMoreElements()) {
          StringBuilder requestBody = new StringBuilder();
          while (i.hasMoreElements()) {
            headerName = i.nextElement();
            httpResponse.addHeader("X-" + headerName, httpRequest.getParameter(headerName));
            requestBody.append(headerName);
            requestBody.append("_");
          }

          if (requestBody.length() > 0) {
            String body = requestBody.toString();
            httpResponse.getOutputStream().write(body.getBytes());
          }
        }

        final AsyncContext context = httpRequest.startAsync();
        final ServletInputStream input = httpRequest.getInputStream();
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();

        input.setReadListener(new ReadListener() {

          byte[] buffer = new byte[5 * 1024];

          @Override
          public void onError(Throwable t) {
            t.printStackTrace();
            httpResponse
                    .setStatus(io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
            context.complete();
          }

          @Override
          public void onDataAvailable() throws IOException {
            int len;
            while (input.isReady() && (len = input.read(buffer)) != -1) {
              baos.write(buffer, 0, len);
            }
          }

          @Override
          public void onAllDataRead() throws IOException {
            byte[] requestBodyBytes = baos.toByteArray();
            int total = requestBodyBytes.length;

            httpResponse.addIntHeader("X-" + CONTENT_LENGTH, total);
            String md5 = TestUtils.md5(requestBodyBytes, 0, total);
            httpResponse.addHeader(CONTENT_MD5.toString(), md5);

            httpResponse.getOutputStream().write(requestBodyBytes, 0, total);
            context.complete();
          }
        });
      }
    });
    wrapper.setAsyncSupported(true);
    ctx.addServletMappingDecoded("/*", "webdav");
    tomcat.start();
    port1 = tomcat.getConnector().getLocalPort();
  }

  @AfterClass(alwaysRun = true)
  public void tearDownGlobal() throws Exception {
    tomcat.stop();
  }

  private String getTargetUrl() {
    return String.format("http://localhost:%d/foo/test", port1);
  }

  @Test
  public void testStreamingPutImage() throws Exception {
    try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
      Response response = client.preparePut(getTargetUrl()).setBody(createPublisher(LARGE_IMAGE_BYTES, 2342))
              .execute().get();
      assertEquals(response.getStatusCode(), 200);
      assertEquals(response.getResponseBodyAsBytes(), LARGE_IMAGE_BYTES);
    }
  }

  @Test
  public void testConnectionDoesNotGetClosed() throws Exception {
    // test that we can stream the same request multiple times
    try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
      BoundRequestBuilder requestBuilder = client.preparePut(getTargetUrl())//
              .setBody(createPublisher(LARGE_IMAGE_BYTES, 1000))//
              .setHeader("X-" + CONTENT_LENGTH, LARGE_IMAGE_BYTES.length)//
              .setHeader("X-" + CONTENT_MD5, LARGE_IMAGE_BYTES_MD5);

      Response response = requestBuilder.execute().get();
      assertEquals(response.getStatusCode(), 200, "HTTP response was invalid on first request.");

      byte[] responseBody = response.getResponseBodyAsBytes();
      assertEquals(Integer.valueOf(response.getHeader("X-" + CONTENT_LENGTH)).intValue(),
              LARGE_IMAGE_BYTES.length, "Server side payload length invalid");
      assertEquals(responseBody.length, LARGE_IMAGE_BYTES.length, "Client side payload length invalid");
      assertEquals(response.getHeader(CONTENT_MD5), LARGE_IMAGE_BYTES_MD5, "Server side payload MD5 invalid");
      assertEquals(TestUtils.md5(responseBody), LARGE_IMAGE_BYTES_MD5, "Client side payload MD5 invalid");
      assertEquals(responseBody, LARGE_IMAGE_BYTES, "Image bytes are not equal on first attempt");

      response = requestBuilder.execute().get();
      assertEquals(response.getStatusCode(), 200);
      responseBody = response.getResponseBodyAsBytes();
      assertEquals(Integer.valueOf(response.getHeader("X-" + CONTENT_LENGTH)).intValue(),
              LARGE_IMAGE_BYTES.length, "Server side payload length invalid");
      assertEquals(responseBody.length, LARGE_IMAGE_BYTES.length, "Client side payload length invalid");

      try {
        assertEquals(response.getHeader(CONTENT_MD5), LARGE_IMAGE_BYTES_MD5, "Server side payload MD5 invalid");
        assertEquals(TestUtils.md5(responseBody), LARGE_IMAGE_BYTES_MD5, "Client side payload MD5 invalid");
        assertEquals(responseBody, LARGE_IMAGE_BYTES, "Image bytes weren't equal on subsequent test");
      } catch (AssertionError e) {
        e.printStackTrace();
        for (int i = 0; i < LARGE_IMAGE_BYTES.length; i++) {
          assertEquals(responseBody[i], LARGE_IMAGE_BYTES[i], "Invalid response byte at position " + i);
        }
        throw e;
      }
    }
  }

  @Test(expectedExceptions = ExecutionException.class)
  public void testFailingStream() throws Exception {
    try (AsyncHttpClient client = asyncHttpClient(config().setRequestTimeout(100 * 6000))) {
      Publisher<ByteBuf> failingPublisher = Flowable.error(new FailedStream());
      client.preparePut(getTargetUrl()).setBody(failingPublisher).execute().get();
    }
  }

  @Test
  public void streamedResponseTest() throws Throwable {
    try (AsyncHttpClient c = asyncHttpClient()) {

      SimpleSubscriber<HttpResponseBodyPart> subscriber = new SimpleSubscriber<>();
      ListenableFuture<Void> future = c.preparePost(getTargetUrl())
              .setBody(LARGE_IMAGE_BYTES).execute(new SimpleStreamedAsyncHandler(subscriber));

      // block
      future.get();
      assertEquals(getBytes(subscriber.getElements()), LARGE_IMAGE_BYTES);

      // Run it again to check that the pipeline is in a good state
      subscriber = new SimpleSubscriber<>();
      future = c.preparePost(getTargetUrl()).setBody(LARGE_IMAGE_BYTES).execute(new SimpleStreamedAsyncHandler(subscriber));

      future.get();
      assertEquals(getBytes(subscriber.getElements()), LARGE_IMAGE_BYTES);

      // Make sure a regular request still works
      assertEquals(c.preparePost(getTargetUrl()).setBody("Hello").execute().get().getResponseBody(), "Hello");

    }
  }

  @Test
  public void cancelStreamedResponseTest() throws Throwable {
    try (AsyncHttpClient c = asyncHttpClient()) {

      // Cancel immediately
      c.preparePost(getTargetUrl()).setBody(LARGE_IMAGE_BYTES).execute(new CancellingStreamedAsyncProvider(0))
              .get();

      // Cancel after 1 element
      c.preparePost(getTargetUrl()).setBody(LARGE_IMAGE_BYTES).execute(new CancellingStreamedAsyncProvider(1))
              .get();

      // Cancel after 10 elements
      c.preparePost(getTargetUrl()).setBody(LARGE_IMAGE_BYTES).execute(new CancellingStreamedAsyncProvider(10))
              .get();

      // Make sure a regular request works
      assertEquals(c.preparePost(getTargetUrl()).setBody("Hello").execute().get().getResponseBody(), "Hello");
    }
  }

  static class SimpleStreamedAsyncHandler implements StreamedAsyncHandler<Void> {
    private final Subscriber<HttpResponseBodyPart> subscriber;

    SimpleStreamedAsyncHandler(Subscriber<HttpResponseBodyPart> subscriber) {
      this.subscriber = subscriber;
    }

    @Override
    public State onStream(Publisher<HttpResponseBodyPart> publisher) {
      publisher.subscribe(subscriber);
      return State.CONTINUE;
    }

    @Override
    public void onThrowable(Throwable t) {
      throw new AssertionError(t);
    }

    @Override
    public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
      throw new AssertionError("Should not have received body part");
    }

    @Override
    public State onStatusReceived(HttpResponseStatus responseStatus) {
      return State.CONTINUE;
    }

    @Override
    public State onHeadersReceived(HttpHeaders headers) {
      return State.CONTINUE;
    }

    @Override
    public Void onCompleted() {
      return null;
    }
  }

  /**
   * Simple subscriber that requests and buffers one element at a time.
   */
  static class SimpleSubscriber<T> implements Subscriber<T> {
    private final List<T> elements = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile Subscription subscription;
    private volatile Throwable error;

    @Override
    public void onSubscribe(Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(T t) {
      elements.add(t);
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
      latch.countDown();
    }

    @Override
    public void onComplete() {
      latch.countDown();
    }

    List<T> getElements() throws Throwable {
      latch.await();
      if (error != null) {
        throw error;
      } else {
        return elements;
      }
    }
  }

  static class CancellingStreamedAsyncProvider implements StreamedAsyncHandler<CancellingStreamedAsyncProvider> {
    private final int cancelAfter;

    CancellingStreamedAsyncProvider(int cancelAfter) {
      this.cancelAfter = cancelAfter;
    }

    @Override
    public State onStream(Publisher<HttpResponseBodyPart> publisher) {
      publisher.subscribe(new CancellingSubscriber<>(cancelAfter));
      return State.CONTINUE;
    }

    @Override
    public void onThrowable(Throwable t) {
      throw new AssertionError(t);
    }

    @Override
    public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
      throw new AssertionError("Should not have received body part");
    }

    @Override
    public State onStatusReceived(HttpResponseStatus responseStatus) {
      return State.CONTINUE;
    }

    @Override
    public State onHeadersReceived(HttpHeaders headers) {
      return State.CONTINUE;
    }

    @Override
    public CancellingStreamedAsyncProvider onCompleted() {
      return this;
    }
  }

  /**
   * Simple subscriber that cancels after receiving n elements.
   */
  static class CancellingSubscriber<T> implements Subscriber<T> {
    private final int cancelAfter;
    private volatile Subscription subscription;
    private AtomicInteger count = new AtomicInteger(0);

    CancellingSubscriber(int cancelAfter) {
      this.cancelAfter = cancelAfter;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      this.subscription = subscription;
      if (cancelAfter == 0) {
        subscription.cancel();
      } else {
        subscription.request(1);
      }
    }

    @Override
    public void onNext(T t) {
      if (count.incrementAndGet() == cancelAfter) {
        subscription.cancel();
      } else {
        subscription.request(1);
      }
    }

    @Override
    public void onError(Throwable error) {
    }

    @Override
    public void onComplete() {
    }
  }

  static class ByteBufIterable implements Iterable<ByteBuf> {
    private final byte[] payload;
    private final int chunkSize;

    ByteBufIterable(byte[] payload, int chunkSize) {
      this.payload = payload;
      this.chunkSize = chunkSize;
    }

    @Override
    public Iterator<ByteBuf> iterator() {
      return new Iterator<ByteBuf>() {
        private int currentIndex = 0;

        @Override
        public boolean hasNext() {
          return currentIndex != payload.length;
        }

        @Override
        public ByteBuf next() {
          int thisCurrentIndex = currentIndex;
          int length = Math.min(chunkSize, payload.length - thisCurrentIndex);
          currentIndex += length;
          return Unpooled.wrappedBuffer(payload, thisCurrentIndex, length);
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException("ByteBufferIterable's iterator does not support remove.");
        }
      };
    }
  }

  @SuppressWarnings("serial")
  private class FailedStream extends RuntimeException {
  }
}
