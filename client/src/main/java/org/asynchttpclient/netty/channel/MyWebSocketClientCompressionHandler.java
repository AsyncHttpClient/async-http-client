package org.asynchttpclient.netty.channel;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtensionHandler;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.DeflateFrameClientExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker;


@Sharable
public class MyWebSocketClientCompressionHandler extends MyWebSocketClientExtensionHandler {

    public static final MyWebSocketClientCompressionHandler INSTANCE = new MyWebSocketClientCompressionHandler();

    private MyWebSocketClientCompressionHandler() {
        super(new MyPerMessageDeflateClientExtensionHandshaker(6, ZlibCodecFactory.isSupportingWindowSizeAndMemLevel(), 15, false, true),
                new DeflateFrameClientExtensionHandshaker(false),
                new DeflateFrameClientExtensionHandshaker(true));
    }
}
