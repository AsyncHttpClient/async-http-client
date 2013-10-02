/*
 * Copyright 2010-2013 Ning, Inc.
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
package org.asynchttpclient.providers.netty.request;

import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicLong;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ProgressAsyncHandler;
import org.asynchttpclient.Realm;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.future.NettyResponseFutures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProgressListener implements ChannelProgressiveFutureListener {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ProgressListener.class);

    private final AsyncHttpClientConfig config;
    private final boolean notifyHeaders;
    private final AsyncHandler<?> asyncHandler;
    private final NettyResponseFuture<?> future;
    private final AtomicLong lastProgress = new AtomicLong(0);

    public ProgressListener(AsyncHttpClientConfig config, boolean notifyHeaders, AsyncHandler<?> asyncHandler, NettyResponseFuture<?> future) {
        this.config = config;
        this.notifyHeaders = notifyHeaders;
        this.asyncHandler = asyncHandler;
        this.future = future;
    }

    @Override
    public void operationComplete(ChannelProgressiveFuture cf) {
        // The write operation failed. If the channel was cached, it means it got asynchronously closed.
        // Let's retry a second time.
        Throwable cause = cf.cause();
        if (cause != null && future.getState() != NettyResponseFuture.STATE.NEW) {

            if (cause instanceof IllegalStateException) {
                LOGGER.debug(cause.getMessage(), cause);
                try {
                    cf.channel().close();
                } catch (RuntimeException ex) {
                    LOGGER.debug(ex.getMessage(), ex);
                }
                return;
            }

            if (cause instanceof ClosedChannelException || NettyResponseFutures.abortOnReadCloseException(cause) || NettyResponseFutures.abortOnWriteCloseException(cause)) {

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(cf.cause() == null ? "" : cf.cause().getMessage(), cf.cause());
                }

                try {
                    cf.channel().close();
                } catch (RuntimeException ex) {
                    LOGGER.debug(ex.getMessage(), ex);
                }
                return;
            } else {
                future.abort(cause);
            }
            return;
        }
        future.touch();

        /**
         * We need to make sure we aren't in the middle of an authorization process before publishing events as we will re-publish again the same event after the authorization,
         * causing unpredictable behavior.
         */
        Realm realm = future.getRequest().getRealm() != null ? future.getRequest().getRealm() : config.getRealm();
        boolean startPublishing = future.isInAuth() || realm == null || realm.getUsePreemptiveAuth();

        if (startPublishing && asyncHandler instanceof ProgressAsyncHandler) {
            if (notifyHeaders) {
                ProgressAsyncHandler.class.cast(asyncHandler).onHeaderWriteCompleted();
            } else {
                ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteCompleted();
            }
        }
    }

    @Override
    public void operationProgressed(ChannelProgressiveFuture f, long progress, long total) {
        future.touch();
        if (!notifyHeaders && asyncHandler instanceof ProgressAsyncHandler) {
            long lastProgressValue = lastProgress.getAndSet(progress);
            ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteProgress(progress - lastProgressValue, progress, total);
        }
    }
}
