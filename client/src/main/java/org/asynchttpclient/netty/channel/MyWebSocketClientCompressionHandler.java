package org.asynchttpclient.netty.channel;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtensionHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.DeflateFrameClientExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker;

import java.util.List;

import static java.util.Arrays.asList;

/**
 * CompressionHandler вместо WebSocketClientCompressionHandler
 * for bet365 live WebSocketClientCompressionHandler - не подходит т.к.
 * в PerMessageDeflateClientExtensionHandshaker нужно передать requestedServerNoContext=true
 */
@Sharable
public class MyWebSocketClientCompressionHandler extends WebSocketClientExtensionHandler {

    private static final List<Integer> specialBkIds = asList(10, 34);

    public static final MyWebSocketClientCompressionHandler INSTANCE = new MyWebSocketClientCompressionHandler();

    private MyWebSocketClientCompressionHandler() {
        super(new PerMessageDeflateClientExtensionHandshaker(
                        6,
                        ZlibCodecFactory.isSupportingWindowSizeAndMemLevel(),
                        15,
                        true,
                        specialBkIds.contains(CurrentBk.getBkId())
                ),
                new DeflateFrameClientExtensionHandshaker(false),
                new DeflateFrameClientExtensionHandshaker(true));
    }
}
