package org.asynchttpclient.providers.netty4;

import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;

import java.nio.channels.ClosedChannelException;

import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.ProgressAsyncHandler;
import org.asynchttpclient.Realm;

public class ProgressListener implements ChannelProgressiveFutureListener {

    private final AsyncHttpClientConfig config;
    private final boolean notifyHeaders;
    private final AsyncHandler<?> asyncHandler;
    private final NettyResponseFuture<?> future;

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
                NettyAsyncHttpProvider.LOGGER.debug(cause.getMessage(), cause);
                try {
                    cf.channel().close();
                } catch (RuntimeException ex) {
                    NettyAsyncHttpProvider.LOGGER.debug(ex.getMessage(), ex);
                }
                return;
            }

            if (cause instanceof ClosedChannelException || NettyResponseFutures.abortOnReadCloseException(cause) || NettyResponseFutures.abortOnWriteCloseException(cause)) {

                if (NettyAsyncHttpProvider.LOGGER.isDebugEnabled()) {
                    NettyAsyncHttpProvider.LOGGER.debug(cf.cause() == null ? "" : cf.cause().getMessage(), cf.cause());
                }

                try {
                    cf.channel().close();
                } catch (RuntimeException ex) {
                    NettyAsyncHttpProvider.LOGGER.debug(ex.getMessage(), ex);
                }
                return;
            } else {
                future.abort(cause);
            }
            return;
        }
        future.touch();

        /**
         * We need to make sure we aren't in the middle of an authorization process before publishing events as we will re-publish again the same event after the authorization, causing unpredictable behavior.
         */
        Realm realm = future.getRequest().getRealm() != null ? future.getRequest().getRealm() : config.getRealm();
        boolean startPublishing = future.isInAuth() || realm == null || realm.getUsePreemptiveAuth();

        if (startPublishing && asyncHandler instanceof ProgressAsyncHandler) {
            // FIXME WTF
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
        if (asyncHandler instanceof ProgressAsyncHandler) {
            ProgressAsyncHandler.class.cast(asyncHandler).onContentWriteProgress(total - progress, progress, total);
        }
    }
}