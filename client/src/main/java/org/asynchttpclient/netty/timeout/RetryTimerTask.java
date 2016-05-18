package org.asynchttpclient.netty.timeout;

import io.netty.util.Timeout;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.request.NettyRequestSender;

/**
 * Created by charlie.chang on 5/4/16.
 */
public class RetryTimerTask extends TimeoutTimerTask {

    public RetryTimerTask(NettyResponseFuture<?> nettyResponseFuture,
                    NettyRequestSender requestSender, TimeoutsHolder timeoutsHolder) {
        super(nettyResponseFuture, requestSender, timeoutsHolder);
    }

    @Override public void run(Timeout timeout) throws Exception {
        if (done.getAndSet(true) || requestSender.isClosed())
            return;

        if (nettyResponseFuture.isDone() || timeout.isCancelled()) {
            timeoutsHolder.cancel();
            return;
        }

        requestSender.retryImmediately(nettyResponseFuture);
    }
}
