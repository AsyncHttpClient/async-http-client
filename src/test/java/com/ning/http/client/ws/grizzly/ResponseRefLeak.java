/*
 * Copyright (c) 2015 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client.ws.grizzly;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProvider;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.testng.Assert.assertTrue;
import org.testng.annotations.Test;

/**
 *
 */
public class ResponseRefLeak {
    @Test
    public void referencedResponseHC() throws InterruptedException
    {
        AsyncHttpClientConfig config = new AsyncHttpClientConfig.Builder().build();
        AsyncHttpClient client = new AsyncHttpClient(new GrizzlyAsyncHttpProvider(config), config);

        final CountDownLatch responseLatch = new CountDownLatch(1);
        final AtomicReference<Response> responseRef = new AtomicReference<>();

        client.prepareGet("http://www.ning.com/").execute(new AsyncCompletionHandler<Response>()
        {

            @Override
            public Response onCompleted(Response response) throws Exception
            {
                responseLatch.countDown();
                responseRef.set(response);
                return response;
            }

            @Override
            public void onThrowable(Throwable t)
            {
                // Something wrong happened.
            }
        });

        responseLatch.await(5, TimeUnit.SECONDS);
        verifyNotLeaked(new PhantomReference<>(responseRef.getAndSet(null), new ReferenceQueue<>()));
    }

    private void verifyNotLeaked(PhantomReference possibleLeakPhantomRef) throws InterruptedException
    {
        for (int i = 0; i < 10; ++i)
        {
            System.gc();
            Thread.sleep(100);
            if (possibleLeakPhantomRef.isEnqueued())
            {
                break;
            }
        }
        assertTrue(possibleLeakPhantomRef.isEnqueued());
    }
}
