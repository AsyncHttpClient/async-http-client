package org.asynchttpclient.reactivestreams;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseHeaders;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.ListenableFuture;
import org.asynchttpclient.handler.StreamedAsyncHandler;
import org.asynchttpclient.test.TestUtils;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


public class ReactiveStreamsDownLoadTest {
  private int serverPort = 8080;
  private File largeFile;
  private File smallFile;
  @BeforeClass(alwaysRun = true)
  public void setUpBeforeTest() throws Exception {
    largeFile = TestUtils.createTempFile(15 * 1024);
    smallFile = TestUtils.createTempFile(20);
    HttpStaticFileServer.start(serverPort);
  }

  @AfterClass(alwaysRun = true)
  public void tearDown() throws Exception {
    HttpStaticFileServer.shutdown();
  }

  @Test
  public void streamedResponseLargeFileTest() throws Throwable {
    AsyncHttpClient c = new DefaultAsyncHttpClient();
    String largeFileName = "http://127.0.0.1:" + serverPort + "/" + largeFile.getName();
    ListenableFuture<SimpleStreamedAsyncHandler> future = c.prepareGet(largeFileName)
        .execute(new SimpleStreamedAsyncHandler());
    byte[] result = future.get().getBytes();
    System.out.println("Result file size: " + result.length);
    //assert(result.length == largeFile.length());
  }

  @Test
  public void streamedResponseSmallFileTest() throws Throwable {
    AsyncHttpClient c = new DefaultAsyncHttpClient();
    String smallFileName = "http://127.0.0.1:" + serverPort + "/" + smallFile.getName();
    ListenableFuture<SimpleStreamedAsyncHandler> future = c.prepareGet(smallFileName)
        .execute(new SimpleStreamedAsyncHandler());
    byte[] result = future.get().getBytes();
    System.out.println("Result file size: " + result.length);
    //assert(result.length == smallFile.length());
    assert(result.length > 0);
  }

  static protected class SimpleStreamedAsyncHandler implements StreamedAsyncHandler<SimpleStreamedAsyncHandler> {
    private final SimpleSubscriber<HttpResponseBodyPart> subscriber;

    public SimpleStreamedAsyncHandler() {
      this(new SimpleSubscriber<HttpResponseBodyPart>());
    }

    public SimpleStreamedAsyncHandler(SimpleSubscriber<HttpResponseBodyPart> subscriber) {
      this.subscriber = subscriber;
    }
    @Override
    public State onStream(Publisher<HttpResponseBodyPart> publisher) {
      System.out.println("SimpleStreamedAsyncHandleronCompleted onStream");
      publisher.subscribe(subscriber);
      return State.CONTINUE;
    }

    @Override
    public void onThrowable(Throwable t) {
      throw new AssertionError(t);
    }

    @Override
    public State onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
      System.out.println("SimpleStreamedAsyncHandleronCompleted onBodyPartReceived");
      throw new AssertionError("Should not have received body part");
    }

    @Override
    public State onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
      return State.CONTINUE;
    }

    @Override
    public State onHeadersReceived(HttpResponseHeaders headers) throws Exception {
      return State.CONTINUE;
    }

    @Override
    public SimpleStreamedAsyncHandler onCompleted() throws Exception {
      System.out.println("SimpleStreamedAsyncHandleronCompleted onSubscribe");
      return this;
    }

    public byte[] getBytes() throws Throwable {
      List<HttpResponseBodyPart> bodyParts = subscriber.getElements();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      for (HttpResponseBodyPart part : bodyParts) {
        part.writeTo(bytes);
      }
      return bytes.toByteArray();
    }
  }

  /**
   * Simple subscriber that requests and buffers one element at a time.
   */
  static protected class SimpleSubscriber<T> implements Subscriber<T> {
    private volatile Subscription subscription;
    private volatile Throwable error;
    private final List<T> elements = Collections.synchronizedList(new ArrayList<T>());
    private final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void onSubscribe(Subscription subscription) {
      System.out.println("SimpleSubscriber onSubscribe");
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(T t) {
      System.out.println("SimpleSubscriber onNext");
      elements.add(t);
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      System.out.println("SimpleSubscriber onError");
      this.error = error;
      latch.countDown();
    }

    @Override
    public void onComplete() {
      System.out.println("SimpleSubscriber onComplete");
      latch.countDown();
    }

    public List<T> getElements() throws Throwable {
      latch.await();
      if (error != null) {
        throw error;
      } else {
        return elements;
      }
    }
  }

}
