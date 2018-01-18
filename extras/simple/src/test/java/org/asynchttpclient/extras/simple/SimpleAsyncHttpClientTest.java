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
package org.asynchttpclient.extras.simple;

import io.netty.handler.codec.http.HttpHeaders;
import org.asynchttpclient.AbstractBasicTest;
import org.asynchttpclient.Response;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.multipart.ByteArrayPart;
import org.asynchttpclient.uri.Uri;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.*;

public class SimpleAsyncHttpClientTest extends AbstractBasicTest {

  private final static String MY_MESSAGE = "my message";

  @Test
  public void inputStreamBodyConsumerTest() throws Exception {

    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
            .setPooledConnectionIdleTimeout(100)
            .setMaxConnections(50)
            .setRequestTimeout(5 * 60 * 1000)
            .setUrl(getTargetUrl())
            .setHeader("Content-Type", "text/html").build()) {
      Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())));

      Response response = future.get();
      assertEquals(response.getStatusCode(), 200);
      assertEquals(response.getResponseBody(), MY_MESSAGE);
    }
  }

  @Test
  public void stringBuilderBodyConsumerTest() throws Exception {

    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
            .setPooledConnectionIdleTimeout(100)
            .setMaxConnections(50)
            .setRequestTimeout(5 * 60 * 1000)
            .setUrl(getTargetUrl())
            .setHeader("Content-Type", "text/html").build()) {
      StringBuilder s = new StringBuilder();
      Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new AppendableBodyConsumer(s));

      Response response = future.get();
      assertEquals(response.getStatusCode(), 200);
      assertEquals(s.toString(), MY_MESSAGE);
    }
  }

  @Test
  public void byteArrayOutputStreamBodyConsumerTest() throws Exception {

    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
            .setPooledConnectionIdleTimeout(100).setMaxConnections(50)
            .setRequestTimeout(5 * 60 * 1000)
            .setUrl(getTargetUrl())
            .setHeader("Content-Type", "text/html").build()) {
      ByteArrayOutputStream o = new ByteArrayOutputStream(10);
      Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new OutputStreamBodyConsumer(o));

      Response response = future.get();
      assertEquals(response.getStatusCode(), 200);
      assertEquals(o.toString(), MY_MESSAGE);
    }
  }

  @Test
  public void requestByteArrayOutputStreamBodyConsumerTest() throws Exception {

    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl()).build()) {
      ByteArrayOutputStream o = new ByteArrayOutputStream(10);
      Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new OutputStreamBodyConsumer(o));

      Response response = future.get();
      assertEquals(response.getStatusCode(), 200);
      assertEquals(o.toString(), MY_MESSAGE);
    }
  }

  /**
   * See https://issues.sonatype.org/browse/AHC-5
   */
  @Test
  public void testPutZeroBytesFileTest() throws Exception {
    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
            .setPooledConnectionIdleTimeout(100)
            .setMaxConnections(50)
            .setRequestTimeout(5 * 1000)
            .setUrl(getTargetUrl() + "/testPutZeroBytesFileTest.txt")
            .setHeader("Content-Type", "text/plain").build()) {
      File tmpfile = File.createTempFile("testPutZeroBytesFile", ".tmp");
      tmpfile.deleteOnExit();

      Future<Response> future = client.put(new FileBodyGenerator(tmpfile));

      System.out.println("waiting for response");
      Response response = future.get();

      tmpfile.delete();

      assertEquals(response.getStatusCode(), 200);
    }
  }

  @Test
  public void testDerive() throws Exception {
    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().build()) {
      try (SimpleAsyncHttpClient derived = client.derive().build()) {
        assertNotSame(derived, client);
      }
    }
  }

  @Test
  public void testDeriveOverrideURL() throws Exception {
    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl("http://invalid.url").build()) {
      ByteArrayOutputStream o = new ByteArrayOutputStream(10);

      InputStreamBodyGenerator generator = new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes()));
      OutputStreamBodyConsumer consumer = new OutputStreamBodyConsumer(o);

      try (SimpleAsyncHttpClient derived = client.derive().setUrl(getTargetUrl()).build()) {
        Future<Response> future = derived.post(generator, consumer);

        Response response = future.get();
        assertEquals(response.getStatusCode(), 200);
        assertEquals(o.toString(), MY_MESSAGE);
      }
    }
  }

  @Test
  public void testSimpleTransferListener() throws Exception {

    final List<Error> errors = Collections.synchronizedList(new ArrayList<>());

    SimpleAHCTransferListener listener = new SimpleAHCTransferListener() {

      public void onStatus(Uri uri, int statusCode, String statusText) {
        try {
          assertEquals(statusCode, 200);
          assertEquals(uri.toUrl(), getTargetUrl());
        } catch (Error e) {
          errors.add(e);
          throw e;
        }
      }

      public void onHeaders(Uri uri, HttpHeaders headers) {
        try {
          assertEquals(uri.toUrl(), getTargetUrl());
          assertNotNull(headers);
          assertTrue(!headers.isEmpty());
          assertEquals(headers.get("X-Custom"), "custom");
        } catch (Error e) {
          errors.add(e);
          throw e;
        }
      }

      public void onCompleted(Uri uri, int statusCode, String statusText) {
        try {
          assertEquals(statusCode, 200);
          assertEquals(uri.toUrl(), getTargetUrl());
        } catch (Error e) {
          errors.add(e);
          throw e;
        }
      }

      public void onBytesSent(Uri uri, long amount, long current, long total) {
        try {
          assertEquals(uri.toUrl(), getTargetUrl());
          // FIXME Netty bug, see
          // https://github.com/netty/netty/issues/1855
          // assertEquals(total, MY_MESSAGE.getBytes().length);
        } catch (Error e) {
          errors.add(e);
          throw e;
        }
      }

      public void onBytesReceived(Uri uri, long amount, long current, long total) {
        try {
          assertEquals(uri.toUrl(), getTargetUrl());
          assertEquals(total, -1);
        } catch (Error e) {
          errors.add(e);
          throw e;
        }
      }
    };

    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder()
            .setUrl(getTargetUrl())
            .setHeader("Custom", "custom")
            .setListener(listener).build()) {
      ByteArrayOutputStream o = new ByteArrayOutputStream(10);

      InputStreamBodyGenerator generator = new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes()));
      OutputStreamBodyConsumer consumer = new OutputStreamBodyConsumer(o);

      Future<Response> future = client.post(generator, consumer);

      Response response = future.get();

      if (!errors.isEmpty()) {
        for (Error e : errors) {
          e.printStackTrace();
        }
        throw errors.get(0);
      }

      assertEquals(response.getStatusCode(), 200);
      assertEquals(o.toString(), MY_MESSAGE);
    }
  }

  @Test
  public void testNullUrl() throws Exception {

    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().build()) {
      assertTrue(true);
    }
  }

  @Test
  public void testCloseDerivedValidMaster() throws Exception {
    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl()).build()) {
      try (SimpleAsyncHttpClient derived = client.derive().build()) {
        derived.get().get();
      }

      Response response = client.get().get();
      assertEquals(response.getStatusCode(), 200);
    }
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void testCloseMasterInvalidDerived() throws Throwable {
    SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl()).build();
    try (SimpleAsyncHttpClient derived = client.derive().build()) {
      client.close();

      try {
        derived.get().get();
        fail("Expected closed AHC");
      } catch (ExecutionException e) {
        throw e.getCause();
      }
    }

  }

  @Test
  public void testMultiPartPut() throws Exception {
    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl() + "/multipart").build()) {
      Response response = client.put(new ByteArrayPart("baPart", "testMultiPart".getBytes(UTF_8), "application/test", UTF_8, "fileName")).get();

      String body = response.getResponseBody();
      String contentType = response.getHeader("X-Content-Type");

      assertTrue(contentType.contains("multipart/form-data"));

      String boundary = contentType.substring(contentType.lastIndexOf("=") + 1);

      assertTrue(body.startsWith("--" + boundary));
      assertTrue(body.trim().endsWith("--" + boundary + "--"));
      assertTrue(body.contains("Content-Disposition:"));
      assertTrue(body.contains("Content-Type: application/test"));
      assertTrue(body.contains("name=\"baPart"));
      assertTrue(body.contains("filename=\"fileName"));
    }
  }

  @Test
  public void testMultiPartPost() throws Exception {
    try (SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setUrl(getTargetUrl() + "/multipart").build()) {
      Response response = client.post(new ByteArrayPart("baPart", "testMultiPart".getBytes(UTF_8), "application/test", UTF_8, "fileName")).get();

      String body = response.getResponseBody();
      String contentType = response.getHeader("X-Content-Type");

      assertTrue(contentType.contains("multipart/form-data"));

      String boundary = contentType.substring(contentType.lastIndexOf("=") + 1);

      assertTrue(body.startsWith("--" + boundary));
      assertTrue(body.trim().endsWith("--" + boundary + "--"));
      assertTrue(body.contains("Content-Disposition:"));
      assertTrue(body.contains("Content-Type: application/test"));
      assertTrue(body.contains("name=\"baPart"));
      assertTrue(body.contains("filename=\"fileName"));
    }
  }
}
