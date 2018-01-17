/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.request.body;

import io.netty.buffer.Unpooled;
import org.asynchttpclient.*;
import org.asynchttpclient.request.body.generator.FeedableBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.generator.UnboundedQueueFeedableBodyGenerator;
import org.testng.annotations.Test;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;

import static org.asynchttpclient.Dsl.*;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_BYTES;
import static org.asynchttpclient.test.TestUtils.LARGE_IMAGE_FILE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.FileAssert.fail;

public class ChunkingTest extends AbstractBasicTest {

  // So we can just test the returned data is the image,
  // and doesn't contain the chunked delimeters.
  @Test
  public void testBufferLargerThanFileWithStreamBodyGenerator() throws Throwable {
    doTestWithInputStreamBodyGenerator(new BufferedInputStream(Files.newInputStream(LARGE_IMAGE_FILE.toPath()), 400000));
  }

  @Test
  public void testBufferSmallThanFileWithStreamBodyGenerator() throws Throwable {
    doTestWithInputStreamBodyGenerator(new BufferedInputStream(Files.newInputStream(LARGE_IMAGE_FILE.toPath())));
  }

  @Test
  public void testDirectFileWithStreamBodyGenerator() throws Throwable {
    doTestWithInputStreamBodyGenerator(Files.newInputStream(LARGE_IMAGE_FILE.toPath()));
  }

  @Test
  public void testDirectFileWithFeedableBodyGenerator() throws Throwable {
    doTestWithFeedableBodyGenerator(Files.newInputStream(LARGE_IMAGE_FILE.toPath()));
  }

  private void doTestWithInputStreamBodyGenerator(InputStream is) throws Throwable {
    try {
      try (AsyncHttpClient c = asyncHttpClient(httpClientBuilder())) {
        ListenableFuture<Response> responseFuture = c.executeRequest(post(getTargetUrl()).setBody(new InputStreamBodyGenerator(is)));
        waitForAndAssertResponse(responseFuture);
      }
    } finally {
      is.close();
    }
  }

  private void doTestWithFeedableBodyGenerator(InputStream is) throws Throwable {
    try {
      try (AsyncHttpClient c = asyncHttpClient(httpClientBuilder())) {
        final FeedableBodyGenerator feedableBodyGenerator = new UnboundedQueueFeedableBodyGenerator();
        Request r = post(getTargetUrl()).setBody(feedableBodyGenerator).build();
        ListenableFuture<Response> responseFuture = c.executeRequest(r);
        feed(feedableBodyGenerator, is);
        waitForAndAssertResponse(responseFuture);
      }
    } finally {
      is.close();
    }
  }

  private void feed(FeedableBodyGenerator feedableBodyGenerator, InputStream is) throws Exception {
    try (InputStream inputStream = is) {
      byte[] buffer = new byte[512];
      for (int i; (i = inputStream.read(buffer)) > -1; ) {
        byte[] chunk = new byte[i];
        System.arraycopy(buffer, 0, chunk, 0, i);
        feedableBodyGenerator.feed(Unpooled.wrappedBuffer(chunk), false);
      }
    }
    feedableBodyGenerator.feed(Unpooled.EMPTY_BUFFER, true);

  }

  private DefaultAsyncHttpClientConfig.Builder httpClientBuilder() {
    return config()//
            .setKeepAlive(true)//
            .setMaxConnectionsPerHost(1)//
            .setMaxConnections(1)//
            .setConnectTimeout(1000)//
            .setRequestTimeout(1000)//
            .setFollowRedirect(true);
  }

  private void waitForAndAssertResponse(ListenableFuture<Response> responseFuture) throws InterruptedException, java.util.concurrent.ExecutionException {
    Response response = responseFuture.get();
    if (500 == response.getStatusCode()) {
      logger.debug("==============\n" +
              "500 response from call\n" +
              "Headers:" + response.getHeaders() + "\n" +
              "==============\n");
      assertEquals(response.getStatusCode(), 500, "Should have 500 status code");
      assertTrue(response.getHeader("X-Exception").contains("invalid.chunk.length"), "Should have failed due to chunking");
      fail("HARD Failing the test due to provided InputStreamBodyGenerator, chunking incorrectly:" + response.getHeader("X-Exception"));
    } else {
      assertEquals(response.getResponseBodyAsBytes(), LARGE_IMAGE_BYTES);
    }
  }
}
