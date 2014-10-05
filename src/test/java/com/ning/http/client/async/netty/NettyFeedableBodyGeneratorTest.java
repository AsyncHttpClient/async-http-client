/*
 * Copyright (c) 2013-2014 Sonatype, Inc. All rights reserved.
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

package com.ning.http.client.async.netty;

import com.ning.http.client.*;
import com.ning.http.client.async.AbstractBasicTest;
import com.ning.http.client.async.ChunkingTest;
import com.ning.http.client.async.ProviderUtil;
import com.ning.http.client.providers.netty.FeedableBodyGenerator;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import static org.testng.FileAssert.fail;

public class NettyFeedableBodyGeneratorTest extends AbstractBasicTest {

    @Override
    public AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        return ProviderUtil.nettyProvider(config);
    }

    @Test(groups = { "standalone", "default_provider" }, enabled = true)
    public void testPutImageFile() throws Exception {
        File largeFile = getTestFile();
        final FileChannel fileChannel = new FileInputStream(largeFile).getChannel();

        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().setRequestTimeoutInMs(100 * 6000).build();
        AsyncHttpClient client = getAsyncHttpClient(config);
        final FeedableBodyGenerator bodyGenerator = new FeedableBodyGenerator();

        try {
            RequestBuilder builder = new RequestBuilder("PUT")
                    .setUrl(getTargetUrl())
                    .setBody(bodyGenerator);

            ListenableFuture<Response> listenableFuture = client.executeRequest(builder.build());

            boolean repeat = true;
            while (repeat) {
                final ByteBuffer buffer = ByteBuffer.allocate(1024);
                if (fileChannel.read(buffer) > 0) {
                    buffer.flip();
                    bodyGenerator.feed(buffer, false);
                } else {
                    repeat = false;
                }
            }
            ByteBuffer emptyBuffer = ByteBuffer.wrap(new byte[0]);
            bodyGenerator.feed(emptyBuffer, true);

            Response response = listenableFuture.get();
            Assert.assertEquals(200, response.getStatusCode());
            Assert.assertEquals("" + largeFile.length(), response.getHeader("X-TRANSFERRED"));
        } finally {
            fileChannel.close();
            client.close();
        }
    }

    private static File getTestFile() {
        String testResource1 = "300k.png";

        File testResource1File = null;
        try {
            ClassLoader cl = ChunkingTest.class.getClassLoader();
            URL url = cl.getResource(testResource1);
            testResource1File = new File(url.toURI());
        } catch (Throwable e) {
            // TODO Auto-generated catch block
            fail("unable to find " + testResource1);
        }

        return testResource1File;
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {

            public void handle(String arg0, org.eclipse.jetty.server.Request arg1, HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {

                ServletInputStream in = req.getInputStream();
                byte[] b = new byte[8192];

                int count = -1;
                int total = 0;
                while ((count = in.read(b)) != -1) {
                    b = new byte[8192];
                    total += count;
                }
                System.err.println("consumed " + total + " bytes.");

                resp.setStatus(200);
                resp.addHeader("X-TRANSFERRED", String.valueOf(total));
                resp.getOutputStream().flush();
                resp.getOutputStream().close();

                arg1.setHandled(true);

            }
        };
    }


}