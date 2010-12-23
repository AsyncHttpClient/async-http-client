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
package com.ning.http.client;

/**
 * An asynchronous handler or callback which gets invoked as soon as some data is available when
 * processing an asynchronous response.<br/>
 * Callback methods get invoked in the following order:
 * <ol>
 * <li>{@link #onStatusReceived(HttpResponseStatus)},</li>
 * <li>{@link #onHeadersReceived(HttpResponseHeaders)},</li>
 * <li>{@link #onBodyPartReceived(HttpResponseBodyPart)}, which could be invoked multiple times,</li>
 * <li>{@link #onCompleted()}, once the response has been fully read.</li>
 * </ol>
 *
 * Returning a {@link AsyncHandler.STATE#ABORT} from any of those callback methods will interrupt asynchronous response
 * processing, after that only {@link #onCompleted()} is going to be called.
 * <p/>
 *
 * <strong>NOTE:</strong> Sending another asynchronous request from an {@link AsyncHandler} must be done using
 * another thread to avoid potential deadlock inside the {@link com.ning.http.client.AsyncHttpProvider}
 * <p/>
 *
 * The recommended way is to use the {@link java.util.concurrent.ExecutorService} from the {@link AsyncHttpClientConfig#executorService()}:
 * <pre>
 * {@code
 *     &#64;Override
 *         public T onCompleted() throws Exception
 *         &#123;
 *             asyncHttpClient.getConfig().executorService().execute(new Runnable()
 *             &#123;
 *                 public void run()
 *                 &#123;
 *                     asyncHttpClient.prepareGet(...);
 *                 &#125;
 *             &#125;);
 *            return T;
 *         &#125;
 * }
 * </pre>
 *
 * @param <T> Type of object returned by the {@link java.util.concurrent.Future#get}
 */
public interface AsyncHandler<T> {

    public static enum STATE {

        /**
         * Stop the processing.
         */
        ABORT,
        /**
         * Continue the processing
         */
        CONTINUE,

    }
    
    /**
     * Invoked when an unexpected exception occurs during the processing of the response
     *
     * @param t a {@link Throwable}
     */
    void onThrowable(Throwable t);

    /**
     * Invoked as soon as some response body part are received.
     * @param bodyPart response's body part.
     * @throws Exception if something wrong happens
     * @return a {@link STATE} telling to CONTINUE or ABORT the current processing.
     */
    STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception;

    /**
     * Invoked as soon as the HTTP status line has been received
     * @param responseStatus the status code and test of the response
     * @throws Exception if something wrong happens
     * @return a {@link STATE} telling to CONTINUE or ABORT the current processing.
     */
    STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception;

    /**
     * Invoked as soon as the HTTP headers has been received.
     * @param headers the HTTP headers.
     * @throws Exception if something wrong happens
     * @return a {@link STATE} telling to CONTINUE or ABORT the current processing.
     */
    STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception;

    /**
     * Invoked once the HTTP response processing is finished.
     * <p/>
     *
     * Gets always invoked as last callback method.
     *
     * @return T Value that will be returned by the associated {@link java.util.concurrent.Future}
     * @throws Exception if something wrong happens
     */
    T onCompleted() throws Exception;
}
