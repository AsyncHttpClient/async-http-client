/*
 *    Copyright (c) 2014-2024 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.netty.request;

import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;
import org.asynchttpclient.netty.NettyResponseFuture;

public class WriteProgressListener extends WriteListener implements ChannelProgressiveFutureListener {

    private final long expectedTotal;
    private long lastProgress;

    public WriteProgressListener(NettyResponseFuture<?> future, boolean notifyHeaders, long expectedTotal) {
        super(future, notifyHeaders);
        this.expectedTotal = expectedTotal;
    }

    @Override
    public void operationComplete(ChannelProgressiveFuture cf) {
        operationComplete(cf.channel(), cf.cause());
    }

    @Override
    public void operationProgressed(ChannelProgressiveFuture f, long progress, long total) {
        future.touch();

        if (progressAsyncHandler != null && !notifyHeaders) {
            long lastLastProgress = lastProgress;
            lastProgress = progress;
            if (total < 0) {
                total = expectedTotal;
            }
            if (progress != lastLastProgress) {
                progressAsyncHandler.onContentWriteProgress(progress - lastLastProgress, progress, total);
            }
        }
    }
}
