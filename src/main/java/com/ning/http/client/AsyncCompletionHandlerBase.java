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
 *
 */
package com.ning.http.client;

import com.ning.http.client.logging.LogManager;
import com.ning.http.client.logging.Logger;

/**
 *  Simple {@link AsyncHandler} of type {@link Response}
 *
 * <strong>NOTE:<strong> Sending another asynchronous request from an {@link AsyncHandler} must be done using
 * another thread to avoid potential deadlock inside the {@link com.ning.http.client.AsyncHttpProvider}
 *
 * The recommended way is to use the {@link java.util.concurrent.ExecutorService} from the {@link com.ning.http.client.AsyncHttpClientConfig}:
 * {@code
 *         &#64;Override
 *         public Response onCompleted(Response response) throws Exception
 *         &#123;
 *             asyncHttpClient.getConfig().executorService().execute(new Runnable()
 *             &#123;
 *                 public void run()
 *                 &#123;
 *                     asyncHttpClient.prepareGet(...);
 *                 &#125;
 *             &#125;);
 *            return response;
 *         &#125;
 * }
 */
public class AsyncCompletionHandlerBase extends AsyncCompletionHandler<Response>{
    private final Logger log = LogManager.getLogger(AsyncCompletionHandlerBase.class);

    @Override
    public Response onCompleted(Response response) throws Exception {
        return response;
    }

    /* @Override */
    public void onThrowable(Throwable t) {
        log.debug(t);
    }
}
