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
package com.ning.http.client.async;

import static java.nio.charset.StandardCharsets.*;

import static junit.framework.Assert.assertTrue;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNotSame;

import org.testng.annotations.Test;

import com.ning.http.client.Response;
import com.ning.http.client.SimpleAsyncHttpClient;
import com.ning.http.client.consumers.AppendableBodyConsumer;
import com.ning.http.client.consumers.OutputStreamBodyConsumer;
import com.ning.http.client.generators.FileBodyGenerator;
import com.ning.http.client.generators.InputStreamBodyGenerator;
import com.ning.http.client.multipart.ByteArrayPart;
import com.ning.http.client.simple.HeaderMap;
import com.ning.http.client.simple.SimpleAHCTransferListener;
import com.ning.http.client.uri.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Future;

public abstract class SimpleAsyncHttpClientTest extends AbstractBasicTest {

    private final static String MY_MESSAGE = "my message";
    
    public abstract String getProviderClass();

    @Test(groups = { "standalone", "default_provider" })
    public void inputStreamBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setPooledConnectionIdleTimeout(100).setMaximumConnectionsTotal(50).setRequestTimeout(5 * 60 * 1000).setUrl(getTargetUrl()).setHeader("Content-Type", "text/html").build();
        try {
            Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())));

            System.out.println("waiting for response");
            Response response = future.get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(response.getResponseBody(), MY_MESSAGE);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void stringBuilderBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setPooledConnectionIdleTimeout(100).setMaximumConnectionsTotal(50).setRequestTimeout(5 * 60 * 1000).setUrl(getTargetUrl()).setHeader("Content-Type", "text/html").build();
        try {
            StringBuilder s = new StringBuilder();
            Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new AppendableBodyConsumer(s));

            System.out.println("waiting for response");
            Response response = future.get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(s.toString(), MY_MESSAGE);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void byteArrayOutputStreamBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setPooledConnectionIdleTimeout(100).setMaximumConnectionsTotal(50).setRequestTimeout(5 * 60 * 1000).setUrl(getTargetUrl()).setHeader("Content-Type", "text/html").build();
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(10);
            Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new OutputStreamBodyConsumer(o));

            System.out.println("waiting for response");
            Response response = future.get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(o.toString(), MY_MESSAGE);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void requestByteArrayOutputStreamBodyConsumerTest() throws Throwable {

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setUrl(getTargetUrl()).build();
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(10);
            Future<Response> future = client.post(new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes())), new OutputStreamBodyConsumer(o));

            System.out.println("waiting for response");
            Response response = future.get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(o.toString(), MY_MESSAGE);
        } finally {
            client.close();
        }
    }

    /**
     * See https://issues.sonatype.org/browse/AHC-5
     */
    @Test(groups = { "standalone", "default_provider" }, enabled = true)
    public void testPutZeroBytesFileTest() throws Throwable {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setPooledConnectionIdleTimeout(100).setMaximumConnectionsTotal(50).setRequestTimeout(5 * 1000).setUrl(getTargetUrl() + "/testPutZeroBytesFileTest.txt").setHeader("Content-Type", "text/plain")
                .build();
        try {
            File tmpfile = File.createTempFile("testPutZeroBytesFile", ".tmp");
            tmpfile.deleteOnExit();

            Future<Response> future = client.put(new FileBodyGenerator(tmpfile));

            Response response = future.get();

            tmpfile.delete();

            assertEquals(response.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testDerive() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).build();
        SimpleAsyncHttpClient derived = client.derive().build();
        try {
            assertNotSame(derived, client);
        } finally {
            client.close();
            derived.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testDeriveOverrideURL() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setUrl("http://invalid.url").build();
        SimpleAsyncHttpClient derived = client.derive().setUrl(getTargetUrl()).build();
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(10);

            InputStreamBodyGenerator generator = new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes()));
            OutputStreamBodyConsumer consumer = new OutputStreamBodyConsumer(o);

            Future<Response> future = derived.post(generator, consumer);

            Response response = future.get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(o.toString(), MY_MESSAGE);
        } finally {
            client.close();
            derived.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testSimpleTransferListener() throws Exception {

        SimpleAHCTransferListener listener = new SimpleAHCTransferListener() {

            public void onStatus(Uri uri, int statusCode, String statusText) {
                assertEquals(statusCode, 200);
                assertEquals(uri.toUrl(), getTargetUrl());
            }

            public void onHeaders(Uri uri, HeaderMap headers) {
                assertEquals(uri.toUrl(), getTargetUrl());
                assertNotNull(headers);
                assertTrue(!headers.isEmpty());
                assertEquals(headers.getFirstValue("X-Custom"), "custom");
            }

            public void onCompleted(Uri uri, int statusCode, String statusText) {
                assertEquals(statusCode, 200);
                assertEquals(uri.toUrl(), getTargetUrl());
            }

            public void onBytesSent(Uri uri, long amount, long current, long total) {
                assertEquals(uri.toUrl(), getTargetUrl());
                assertEquals(total, MY_MESSAGE.getBytes().length);
            }

            public void onBytesReceived(Uri uri, long amount, long current, long total) {
                assertEquals(uri.toUrl(), getTargetUrl());
                assertEquals(total, -1);
            }
        };

        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setUrl(getTargetUrl()).setHeader("Custom", "custom").setListener(listener).build();
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(10);

            InputStreamBodyGenerator generator = new InputStreamBodyGenerator(new ByteArrayInputStream(MY_MESSAGE.getBytes()));
            OutputStreamBodyConsumer consumer = new OutputStreamBodyConsumer(o);

            Future<Response> future = client.post(generator, consumer);

            Response response = future.get();
            assertEquals(response.getStatusCode(), 200);
            assertEquals(o.toString(), MY_MESSAGE);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testNullUrl() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).build().derive().build();
        try {
            assertTrue(true);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testCloseDerivedValidMaster() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setUrl(getTargetUrl()).build();
        try {
            SimpleAsyncHttpClient derived = client.derive().build();
            derived.get().get();

            derived.close();

            Response response = client.get().get();

            assertEquals(response.getStatusCode(), 200);
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testCloseMasterInvalidDerived() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setUrl(getTargetUrl()).build();
        SimpleAsyncHttpClient derived = client.derive().build();

        client.close();

        try {
            derived.get().get();
            fail("Expected closed AHC");
        } catch (IOException e) {
            // expected -- Seems to me that this behavior conflicts with the requirements of Future.get()
        } finally {
            client.close();
            derived.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testMultiPartPut() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setUrl(getTargetUrl() + "/multipart").build();
        try {
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
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testMultiPartPost() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setUrl(getTargetUrl() + "/multipart").build();
        try {
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
        } finally {
            client.close();
        }
    }
}
