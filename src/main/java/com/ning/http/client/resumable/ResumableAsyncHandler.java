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
package com.ning.http.client.resumable;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.listener.TransferCompletionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link AsyncHandler} which support resumable download, e.g when used with an {@link ResumableIOExceptionFilter},
 * this handler can resume the download operation at the point it was before the interruption occured. This prevent having to
 * download the entire file again. Implementation of {@link com.ning.http.client.listener.TransferListener} are used to handle the received bytes, and those
 * listeners are guarantee to get the bytes in the right order, without duplication. It's the responsibility of the {@link com.ning.http.client.listener.TransferListener}
 * to track how many bytes has been transferred and to properly adjust the file's write position.
 *
 * In case of a JVM crash/shutdown, you can create an instance of this class and pass the last valid bytes position.
 */
public class ResumableAsyncHandler extends TransferCompletionHandler {
    private final static Logger logger = LoggerFactory.getLogger(TransferCompletionHandler.class);
    private final AtomicLong byteTransferred;
    private Integer contentLenght;

    public ResumableAsyncHandler(long byteTransferred) {
        this.byteTransferred = new AtomicLong(byteTransferred);
    }

    public ResumableAsyncHandler() {
        byteTransferred = new AtomicLong();
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public void onThrowable(Throwable t) {
        logger.error("", t);
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public AsyncHandler.STATE onBodyPartReceived(HttpResponseBodyPart bodyPart) throws Exception {
        AsyncHandler.STATE state = super.onBodyPartReceived(bodyPart);
        byteTransferred.addAndGet(bodyPart.getBodyPartBytes().length);
        return state;
    }

    /**
     * {@inheritDoc}
     */
    /* @Override */
    public AsyncHandler.STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
        if (headers.getHeaders().getFirstValue("Content-Length") != null) {
            contentLenght = Integer.valueOf(headers.getHeaders().getFirstValue("Content-Length"));
            if (contentLenght == null || contentLenght == -1) {
                return AsyncHandler.STATE.ABORT;
            }
        }
        return super.onHeadersReceived(headers);
    }

    /**
     * Invoke this API if you want to set the Range header on your {@link Request} based on the last valid bytes
     * position.
     *
     * @param request {@link Request}
     * @return a {@link Request} with the Range header properly set.
     */
    public Request adjustRequestRange(Request request) {
        if (byteTransferred.get() == 0) {
            throw new IllegalStateException("No bytes transferred");
        }

        RequestBuilder builder = new RequestBuilder(request);
        builder.setHeader("Range", "bytes=" + byteTransferred.get() + "-");
        return builder.build();
    }
}
