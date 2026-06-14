/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler.intercept;

import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.OnLastHttpContentCallback;
import org.asynchttpclient.netty.channel.Channels;
import org.asynchttpclient.netty.request.NettyRequestSender;

class Continue100Interceptor {

    private final NettyRequestSender requestSender;

    Continue100Interceptor(NettyRequestSender requestSender) {
        this.requestSender = requestSender;
    }

    public boolean exitAfterHandling100(final Channel channel, final NettyResponseFuture<?> future) {
        // Capture whether THIS request actually deferred its body for a 100-continue, before resetting the
        // flag below. A server may send an (unsolicited) 100 even when the client did not send
        // Expect: 100-continue (RFC 9110 §15.2.1); in that case the body was already sent and must not be
        // sent a second time.
        boolean bodyWasDeferred = future.isDontWriteBodyBecauseExpectContinue();
        future.setHeadersAlreadyWrittenOnContinue(true);
        future.setDontWriteBodyBecauseExpectContinue(false);

        if (channel instanceof Http2StreamChannel) {
            // HTTP/2: the HEADERS frame was already sent with endStream=false; now send the deferred body
            // as DATA frame(s). writeRequest() can't be reused here — its isHttp2() check looks for the
            // parent connection's multiplex handler, which a stream child channel doesn't have, so it would
            // mis-route to the HTTP/1.1 writer (UnsupportedMessageTypeException + use-after-free).
            //
            // Only resume when the body was genuinely deferred. For a request WITHOUT Expect: 100-continue
            // the body was already written with endStream=true, so writing DATA now would be a frame after
            // endStream on a half-closed (local) stream — a STREAM_CLOSED protocol error (RFC 9113 §5.1).
            // An unsolicited interim 100 on such a request is ignored (keep waiting for the final response).
            if (bodyWasDeferred) {
                try {
                    requestSender.sendHttp2RequestBody(future, (Http2StreamChannel) channel);
                } catch (Exception e) {
                    requestSender.abort(channel, future, e);
                }
            }
        } else {
            // HTTP/1.1: wait for LastHttpContent before sending the body
            Channels.setAttribute(channel, new OnLastHttpContentCallback(future) {
                @Override
                public void call() {
                    Channels.setAttribute(channel, future);
                    requestSender.writeRequest(future, channel);
                }
            });
        }
        return true;
    }
}
