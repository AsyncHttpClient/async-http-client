package org.asynchttpclient.request.body.generator;

import org.asynchttpclient.request.body.Body;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.testng.Assert.*;

public class FeedableBodyGeneratorTest {

  private FeedableBodyGenerator feedableBodyGenerator;
  private TestFeedListener listener;

  @BeforeMethod
  public void setUp() throws Exception {
    feedableBodyGenerator = new FeedableBodyGenerator();
    listener = new TestFeedListener();
    feedableBodyGenerator.setListener(listener);
  }

  @Test(groups = "standalone")
  public void feedNotifiesListener() throws Exception {
    feedableBodyGenerator.feed(ByteBuffer.allocate(0), false);
    feedableBodyGenerator.feed(ByteBuffer.allocate(0), true);
    assertEquals(listener.getCalls(), 2);
  }

  @Test(groups = "standalone")
  public void readingBytesReturnsFedContentWithEmptyLastBuffer() throws Exception {
    byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);
    feedableBodyGenerator.feed(ByteBuffer.wrap(content), false);
    feedableBodyGenerator.feed(ByteBuffer.allocate(0), true);
    Body body = feedableBodyGenerator.createBody();
    assertEquals(readFromBody(body), "7\r\nTest123\r\n".getBytes(StandardCharsets.US_ASCII));
    assertEquals(readFromBody(body), "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    assertEquals(body.read(ByteBuffer.allocate(1)), -1);

  }

  @Test(groups = "standalone")
  public void readingBytesReturnsFedContentWithFilledLastBuffer() throws Exception {
    byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);
    feedableBodyGenerator.feed(ByteBuffer.wrap(content), true);
    Body body = feedableBodyGenerator.createBody();
    assertEquals(readFromBody(body), "7\r\nTest123\r\n".getBytes(StandardCharsets.US_ASCII));
    assertEquals(readFromBody(body), "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    assertEquals(body.read(ByteBuffer.allocate(1)), -1);

  }

  @Test(groups = "standalone")
  public void readingBytesReturnsFedContentWithoutChunkBoundariesWhenDisabled() throws Exception {
    byte[] content = "Test123".getBytes(StandardCharsets.US_ASCII);
    feedableBodyGenerator.setWriteChunkBoundaries(false);
    feedableBodyGenerator.feed(ByteBuffer.wrap(content), true);
    Body body = feedableBodyGenerator.createBody();
    assertEquals(readFromBody(body), "Test123".getBytes(StandardCharsets.US_ASCII));
    assertEquals(body.read(ByteBuffer.allocate(1)), -1);

  }

  private byte[] readFromBody(Body body) throws IOException {
    ByteBuffer byteBuffer = ByteBuffer.allocate(512);
    long read = body.read(byteBuffer);
    byteBuffer.flip();
    byte[] readBytes = new byte[byteBuffer.remaining()];
    byteBuffer.get(readBytes);
    return readBytes;
  }

  private static class TestFeedListener implements FeedableBodyGenerator.FeedListener {

    private int calls;
    @Override
    public void onContentAdded() {
      calls++;
    }

    public int getCalls() {
      return calls;
    }
  }
}