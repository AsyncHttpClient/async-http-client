package org.asynchttpclient.netty.channel;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtensionHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.DeflateFrameClientExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker;

/**CompressionHandler вместо WebSocketClientCompressionHandler
 * for bet365 live WebSocketClientCompressionHandler - не подходит т.к.
 * в PerMessageDeflateClientExtensionHandshaker нужно передать requestedServerNoContext=true
 * */
@Sharable
public class MyWebSocketClientCompressionHandler extends WebSocketClientExtensionHandler {

    public static final MyWebSocketClientCompressionHandler INSTANCE = new MyWebSocketClientCompressionHandler();

    private MyWebSocketClientCompressionHandler() {
        super(new PerMessageDeflateClientExtensionHandshaker(
                6,
                        ZlibCodecFactory.isSupportingWindowSizeAndMemLevel(),
                        15,
                        true,
                        CurrentBk.getBkId() == 10
                ),
                new DeflateFrameClientExtensionHandshaker(false),
                new DeflateFrameClientExtensionHandshaker(true));
    }
}
