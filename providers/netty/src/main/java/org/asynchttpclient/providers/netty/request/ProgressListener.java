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

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ProgressAsyncHandler;
import org.asynchttpclient.Realm;
import org.asynchttpclient.providers.netty.future.NettyResponseFuture;
import org.asynchttpclient.providers.netty.future.StackTraceInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;

import java.nio.channels.ClosedChannelException;

public class ProgressListener implements ChannelProgressiveFutureListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProgressListener.class);

    private final AsyncHttpClientConfig config;
    private final AsyncHandler<?> asyncHandler;
    private final NettyResponseFuture<?> future;
    private final boolean notifyHeaders;
    private final long expectedTotal;
    private long lastProgress = 0L;

    public ProgressListener(AsyncHttpClientConfig config, AsyncHandler<?> asyncHandler, NettyResponseFuture<?> future,
            boolean notifyHeaders, long expectedTotal) {
        this.config = config;
        this.asyncHandler = asyncHandler;
        this.future = future;
        this.notifyHeaders = notifyHeaders;
        this.expectedTotal = expectedTotal;
    }

    private boolean abortOnThrowable(Throwable cause, Channel channel) {

        if (cause != null && future.getState() != NettyResponseFuture.STATE.NEW) {

            if (cause instanceof IllegalStateException) {
                LOGGER.debug(cause.getMessage(), cause);
                try {
                    channel.close();
                } catch (RuntimeException ex) {
                    LOGGER.debug(ex.getMessage(), ex);
                }
            } else if (cause instanceof ClosedChannelException || StackTraceInspector.abortOnReadOrWriteException(cause)) {

                if (LOGGER.isDebugEnabled())
                    LOGGER.debug(cause.getMessage(), cause);

                try {
                    channel.close();
                } catch (RuntimeException ex) {
                    LOGGER.debug(ex.getMessage(), ex);
                }
            } else {
                future.abort(cause);
            }

            return true;
        }

        return false;
    }

    @Override
    public void operationComplete(ChannelProgressiveFuture cf) {
        // The write operation failed. If the channel was cached, it means it got asynchronously closed.
        // Let's retry a second time.

        if (!abortOnThrowable(cf.cause(), cf.channel())) {

            future.touch();

            /**
             * We need to make sure we aren't in the middle of an authorization process before publishing events as we
             * will re-publish again the same event after the authorization,
             * causing unpredictable behavior.
             */
            Realm realm = future.getRequest().getRealm() != null ? future.getRequest().getRealm() : config.getRealm();
            boolean startPublishing = future.isInAuth() || realm == null || realm.getUsePreemptiveAuth();

            if (startPublishing && asyncHandler instanceof ProgressAsyncHandler) {
                ProgressAsyncHandler<?> progressAsyncHandler = (ProgressAsyncHandler<?>) asyncHandler;
                if (notifyHeaders) {
                    progressAsyncHandler.onHeaderWriteCompleted();
                } else {
                    progressAsyncHandler.onContentWriteCompleted();
                }
            }
        }
    }

    @Override
    public void operationProgressed(ChannelProgressiveFuture f, long progress, long total) {
        future.touch();
        if (!notifyHeaders && asyncHandler instanceof ProgressAsyncHandler) {
            long lastLastProgress = lastProgress;
            lastProgress = progress;
            if (total < 0)
                total = expectedTotal;
            ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteProgress(progress - lastLastProgress, progress, total);
        }
    }
}
