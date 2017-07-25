package org.asynchttpclient.netty.channel;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.CodecException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.extensions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Created by elena on 24.07.17.
 */
public class MyWebSocketClientExtensionHandler extends ChannelDuplexHandler {
    private final List<WebSocketClientExtensionHandshaker> extensionHandshakers;

    public MyWebSocketClientExtensionHandler(WebSocketClientExtensionHandshaker... extensionHandshakers) {
        if(extensionHandshakers == null) {
            throw new NullPointerException("extensionHandshakers");
        } else if(extensionHandshakers.length == 0) {
            throw new IllegalArgumentException("extensionHandshakers must contains at least one handshaker");
        } else {
            this.extensionHandshakers = Arrays.asList(extensionHandshakers);
        }
    }

    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if(msg instanceof HttpRequest && MyWebSocketExtensionUtil.isWebsocketUpgrade(((HttpRequest)msg).headers())) {
            HttpRequest request = (HttpRequest)msg;
            String headerValue = request.headers().getAsString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);

            WebSocketExtensionData extensionData;
            for(Iterator var6 = this.extensionHandshakers.iterator(); var6.hasNext(); headerValue = MyWebSocketExtensionUtil.appendExtension(headerValue, extensionData.name(), extensionData.parameters())) {
                WebSocketClientExtensionHandshaker extensionHandshaker = (WebSocketClientExtensionHandshaker)var6.next();
                extensionData = extensionHandshaker.newRequestData();
            }

            request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, headerValue);
        }

        super.write(ctx, msg, promise);
    }

    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//        System.out.println("ctx.name="+ctx.name()+" msg="+msg.getClass());
        if(msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse)msg;
            if(MyWebSocketExtensionUtil.isWebsocketUpgrade(response.headers())) {
                String extensionsHeader = response.headers().getAsString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
                if(extensionsHeader != null) {
                    List<WebSocketExtensionData> extensions = WebSocketExtensionUtil.extractExtensions(extensionsHeader);
                    List<WebSocketClientExtension> validExtensions = new ArrayList(extensions.size());
                    int rsv = 0;
                    Iterator var8 = extensions.iterator();

                    label51:
                    while(true) {
                        if(!var8.hasNext()) {
                            var8 = validExtensions.iterator();

                            while(true) {
                                if(!var8.hasNext()) {
                                    break label51;
                                }

                                WebSocketClientExtension validExtension = (WebSocketClientExtension)var8.next();
                                WebSocketExtensionDecoder decoder = validExtension.newExtensionDecoder();
                                WebSocketExtensionEncoder encoder = validExtension.newExtensionEncoder();
                                ctx.pipeline().addAfter(ctx.name(), decoder.getClass().getName(), decoder);
//                                ctx.pipeline().addAfter(ctx.name(), encoder.getClass().getName(), encoder);
//                                ctx.pipeline().addAfter("ws-decoder", decoder.getClass().getName(), decoder);
                                ctx.pipeline().addAfter(decoder.getClass().getName(), encoder.getClass().getName(), encoder);
//                                System.out.println("ctx.pipeline().names()"+ctx.pipeline().names());
                            }
                        }

                        WebSocketExtensionData extensionData = (WebSocketExtensionData)var8.next();
                        Iterator<WebSocketClientExtensionHandshaker> extensionHandshakersIterator = this.extensionHandshakers.iterator();

                        WebSocketClientExtension validExtension;
                        WebSocketClientExtensionHandshaker extensionHandshaker;
                        for(validExtension = null; validExtension == null && extensionHandshakersIterator.hasNext(); validExtension = extensionHandshaker.handshakeExtension(extensionData)) {
                            extensionHandshaker = (WebSocketClientExtensionHandshaker)extensionHandshakersIterator.next();
                        }

                        if(validExtension == null || (validExtension.rsv() & rsv) != 0) {
                            throw new CodecException("invalid WebSocket Extension handshake for \"" + extensionsHeader + '"');
                        }

                        rsv |= validExtension.rsv();
                        validExtensions.add(validExtension);
                    }
                }

                ctx.pipeline().remove(ctx.name());
            }
        }

        super.channelRead(ctx, msg);
    }
}
