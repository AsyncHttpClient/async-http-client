/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.netty.request;

import io.netty.channel.Channel;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;

import java.nio.channels.ClosedChannelException;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.handler.ProgressAsyncHandler;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelState;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.future.StackTraceInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProgressListener implements ChannelProgressiveFutureListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProgressListener.class);

    private final AsyncHandler<?> asyncHandler;
    private final NettyResponseFuture<?> future;
    private final boolean notifyHeaders;
    private final long expectedTotal;
    private long lastProgress = 0L;

    public ProgressListener(AsyncHandler<?> asyncHandler,//
            NettyResponseFuture<?> future,//
            boolean notifyHeaders,//
            long expectedTotal) {
        this.asyncHandler = asyncHandler;
        this.future = future;
        this.notifyHeaders = notifyHeaders;
        this.expectedTotal = expectedTotal;
    }

    private boolean abortOnThrowable(Throwable cause, Channel channel) {

        if (cause != null && future.getChannelState() != ChannelState.NEW) {
            if (cause instanceof IllegalStateException || cause instanceof ClosedChannelException || StackTraceInspector.recoverOnReadOrWriteException(cause)) {
                LOGGER.debug(cause.getMessage(), cause);
                Channels.silentlyCloseChannel(channel);
                
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
             * We need to make sure we aren't in the middle of an authorization
             * process before publishing events as we will re-publish again the
             * same event after the authorization, causing unpredictable
             * behavior.
             */
            boolean startPublishing = !future.getInAuth().get() && !future.getInProxyAuth().get();
            
            if (startPublishing && asyncHandler instanceof ProgressAsyncHandler) {
                ProgressAsyncHandler<?> progressAsyncHandler = (ProgressAsyncHandler<?>) asyncHandler;
                if (notifyHeaders) {
                    progressAsyncHandler.onHeadersWritten();
                } else {
                    progressAsyncHandler.onContentWritten();
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
