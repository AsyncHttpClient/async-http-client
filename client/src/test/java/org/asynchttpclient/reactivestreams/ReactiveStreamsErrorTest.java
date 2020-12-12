package org.asynchttpclient.reactivestreams;

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.exception.RemotelyClosedException;
import org.asynchttpclient.handler.StreamedAsyncHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.asynchttpclient.Dsl.asyncHttpClient;
import static org.asynchttpclient.Dsl.config;
import static org.testng.Assert.*;

public class ReactiveStreamsErrorTest extends AbstractBasicTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReactiveStreamsErrorTest.class);

  private static final byte[] BODY_CHUNK = "someBytes".getBytes();

  private AsyncHttpClient client;
  private ServletResponseHandler servletResponseHandler;

  @BeforeTest
  public void initClient() {
    client = asyncHttpClient(config()
            .setMaxRequestRetry(0)
            .setRequestTimeout(3_000)
            .setReadTimeout(1_000));
  }

  @AfterTest
  public void closeClient() throws Throwable {
    client.close();
  }

  @Override
  public AbstractHandler configureHandler() throws Exception {
    return new AbstractHandler() {
      @Override
      public void handle(String target, Request r, HttpServletRequest request, HttpServletResponse response) {
        try {
          servletResponseHandler.handle(response);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  @Test
  public void timeoutWithNoStatusLineSent() throws Throwable {
    try {
      execute(response -> Thread.sleep(5_000), bodyPublisher -> {});
      fail("Request should have timed out");
    } catch (ExecutionException e) {
      expectReadTimeout(e.getCause());
    }
  }

  @Test
  public void neverSubscribingToResponseBodyHitsRequestTimeout() throws Throwable {
    try {
      execute(response -> {
        response.getOutputStream().write(BODY_CHUNK);
        response.getOutputStream().flush();
        Thread.sleep(500);
        response.getOutputStream().write(BODY_CHUNK);
        response.getOutputStream().flush();

        response.getOutputStream().close();
      }, bodyPublisher -> {});

      fail("Request should have timed out");
    } catch (ExecutionException e) {
      expectRequestTimeout(e.getCause());
    }
  }

  @Test
  public void readTimeoutInMiddleOfBody() throws Throwable {
    ServletResponseHandler responseHandler = response -> {
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      Thread.sleep(500);
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      Thread.sleep(5_000);
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      response.getOutputStream().close();
    };

    try {
      execute(responseHandler, bodyPublisher -> bodyPublisher.subscribe(new ManualRequestSubscriber() {
        @Override
        public void onSubscribe(Subscription s) {
          s.request(Long.MAX_VALUE);
        }
      }));
      fail("Request should have timed out");
    } catch (ExecutionException e) {
      expectReadTimeout(e.getCause());
    }
  }

  @Test
  public void notRequestingForLongerThanReadTimeoutDoesNotCauseTimeout() throws Throwable {
    ServletResponseHandler responseHandler = response -> {
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      Thread.sleep(100);
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      response.getOutputStream().close();
    };

    ManualRequestSubscriber subscriber = new ManualRequestSubscriber() {
      @Override
      public void onSubscribe(Subscription s) {
        super.onSubscribe(s);
        new Thread(() -> {
          try {
            // chunk 1
            s.request(1);

            // there will be no read for longer than the read timeout
            Thread.sleep(1_500);

            // read the rest
            s.request(Long.MAX_VALUE);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }).start();
      }
    };

    execute(responseHandler, bodyPublisher -> bodyPublisher.subscribe(subscriber));

    subscriber.await();

    assertEquals(subscriber.elements.size(), 2);
  }

  @Test
  public void readTimeoutCancelsBodyStream() throws Throwable {
    ServletResponseHandler responseHandler = response -> {
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      Thread.sleep(2_000);
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      response.getOutputStream().close();
    };

    ManualRequestSubscriber subscriber = new ManualRequestSubscriber() {
      @Override
      public void onSubscribe(Subscription s) {
        super.onSubscribe(s);
        s.request(Long.MAX_VALUE);
      }
    };

    try {
      execute(responseHandler, bodyPublisher -> bodyPublisher.subscribe(subscriber));
      fail("Request should have timed out");
    } catch (ExecutionException e) {
      expectReadTimeout(e.getCause());
    }

    subscriber.await();

    assertEquals(subscriber.elements.size(), 1);
  }

  @Test
  public void requestTimeoutCancelsBodyStream() throws Throwable {
    ServletResponseHandler responseHandler = response -> {
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      Thread.sleep(900);
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      Thread.sleep(900);
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      Thread.sleep(900);
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      Thread.sleep(900);
      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();
      response.getOutputStream().close();
    };

    ManualRequestSubscriber subscriber = new ManualRequestSubscriber() {
      @Override
      public void onSubscribe(Subscription subscription) {
        super.onSubscribe(subscription);
        subscription.request(Long.MAX_VALUE);
      }
    };

    try {
      execute(responseHandler, bodyPublisher -> bodyPublisher.subscribe(subscriber));
      fail("Request should have timed out");
    } catch (ExecutionException e) {
      expectRequestTimeout(e.getCause());
    }

    subscriber.await();

    expectRequestTimeout(subscriber.error);
    assertEquals(subscriber.elements.size(), 4);
  }

  @Test
  public void ioErrorsArePropagatedToSubscriber() throws Throwable {
    ServletResponseHandler responseHandler = response -> {
      response.setContentLength(100);

      response.getOutputStream().write(BODY_CHUNK);
      response.getOutputStream().flush();

      response.getOutputStream().close();
    };

    ManualRequestSubscriber subscriber = new ManualRequestSubscriber() {
      @Override
      public void onSubscribe(Subscription subscription) {
        super.onSubscribe(subscription);
        subscription.request(Long.MAX_VALUE);
      }
    };

    Throwable error = null;
    try {
      execute(responseHandler, bodyPublisher -> bodyPublisher.subscribe(subscriber));
      fail("Request should have failed");
    } catch (ExecutionException e) {
      error = e.getCause();
      assertTrue(error instanceof RemotelyClosedException, "Unexpected error: " + e);
    }

    subscriber.await();

    assertEquals(subscriber.error, error);
    assertEquals(subscriber.elements.size(), 1);
  }

  private void expectReadTimeout(Throwable e) {
    assertTrue(e instanceof TimeoutException,
            "Expected a read timeout, but got " + e);
    assertTrue(e.getMessage().contains("Read timeout"),
            "Expected read timeout, but was " + e);
  }

  private void expectRequestTimeout(Throwable e) {
    assertTrue(e instanceof TimeoutException,
            "Expected a request timeout, but got " + e);
    assertTrue(e.getMessage().contains("Request timeout"),
            "Expected request timeout, but was " + e);
  }

  private void execute(ServletResponseHandler responseHandler,
                       Consumer<Publisher<HttpResponseBodyPart>> bodyConsumer) throws Exception {
    this.servletResponseHandler = responseHandler;
    client.prepareGet(getTargetUrl())
            .execute(new SimpleStreamer(bodyConsumer))
            .get(3_500, TimeUnit.MILLISECONDS);
  }

  private interface ServletResponseHandler {
    void handle(HttpServletResponse response) throws Exception;
  }

  private static class SimpleStreamer implements StreamedAsyncHandler<Void> {

    final Consumer<Publisher<HttpResponseBodyPart>> bodyStreamHandler;

    private SimpleStreamer(Consumer<Publisher<HttpResponseBodyPart>> bodyStreamHandler) {
      this.bodyStreamHandler = bodyStreamHandler;
    }

    @Override
    public State onStream(Publisher<HttpResponseBodyPart> publisher) {
      LOGGER.debug("Got stream");
      bodyStreamHandler.accept(publisher);
      return State.CONTINUE;
    }

    @Override
    public State onStatusReceived(HttpResponseStatus responseStatus) {
      LOGGER.debug("Got status line");
      return State.CONTINUE;
    }

    @Override
    public State onHeadersReceived(HttpHeaders headers) {
      LOGGER.debug("Got headers");
      return State.CONTINUE;
    }

    @Override
    public State onBodyPartReceived(HttpResponseBodyPart bodyPart) {
      throw new IllegalStateException();
    }

    @Override
    public void onThrowable(Throwable t) {
      LOGGER.debug("Caught error", t);
    }

    @Override
    public Void onCompleted() {
      LOGGER.debug("Completed request");
      return null;
    }
  }

  private static class ManualRequestSubscriber implements Subscriber<HttpResponseBodyPart> {
    private final List<HttpResponseBodyPart> elements = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile Throwable error;

    @Override
    public void onSubscribe(Subscription subscription) {
      LOGGER.debug("SimpleSubscriber onSubscribe");
    }

    @Override
    public void onNext(HttpResponseBodyPart t) {
      LOGGER.debug("SimpleSubscriber onNext");
      elements.add(t);
    }

    @Override
    public void onError(Throwable error) {
      LOGGER.debug("SimpleSubscriber onError");
      this.error = error;
      latch.countDown();
    }

    @Override
    public void onComplete() {
      LOGGER.debug("SimpleSubscriber onComplete");
      latch.countDown();
    }

    void await() throws InterruptedException {
      if (!latch.await(3_500, TimeUnit.MILLISECONDS)) {
        fail("Request should have finished");
      }
    }
  }
}
