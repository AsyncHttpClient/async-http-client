/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Body;
import com.ning.http.client.BodyGenerator;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import static org.testng.Assert.assertEquals;

public abstract class BodyChunkTest extends AbstractBasicTest {

    @Test(groups = {"standalone", "default_provider"})
    public void negativeContentTypeTest() throws Throwable {

        AsyncHttpClientConfig.Builder confbuilder = new AsyncHttpClientConfig.Builder();
        confbuilder = confbuilder.setConnectionTimeoutInMs(100);
        confbuilder = confbuilder.setMaximumConnectionsTotal(50);
        confbuilder = confbuilder.setRequestTimeoutInMs(5 * 60 * 1000); // 5 minutes

        // Create client
        AsyncHttpClient client = getAsyncHttpClient(confbuilder.build());

        RequestBuilder requestBuilder = new RequestBuilder("POST")
                .setUrl(getTargetUrl())
                .setHeader("Content-Type", "message/rfc822");

        requestBuilder.setBody(new StaticBodyGenerator("my message"));

        Future<Response> future = client.executeRequest(requestBuilder.build());

        System.out.println("waiting for response");
        Response response = future.get();
        assertEquals(response.getStatusCode(), 200);
        client.close();
    }

    private static class StaticBodyGenerator implements BodyGenerator {
        private final byte[] bytes;

        public StaticBodyGenerator(String message) {
            bytes = message.getBytes();
        }

        public Body createBody()
                throws IOException {
            return new Body() {

                public long getContentLength() {
                    return -1;
                }

                boolean done;

                public long read(ByteBuffer buffer)
                        throws IOException {
                    if (done) {
                        return -1;
                    }

                    buffer.put(bytes);
                    done = true;
                    return bytes.length;
                }

                public void close()
                        throws IOException {
                    done = true;
                }
            };
        }
    }
}


