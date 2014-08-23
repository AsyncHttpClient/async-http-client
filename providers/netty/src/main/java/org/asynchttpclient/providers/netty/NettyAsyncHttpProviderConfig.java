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
package org.asynchttpclient.providers.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Timer;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.asynchttpclient.AsyncHttpProviderConfig;
import org.asynchttpclient.SSLEngineFactory;
import org.asynchttpclient.providers.netty.channel.pool.ChannelPool;
import org.asynchttpclient.providers.netty.response.EagerNettyResponseBodyPart;
import org.asynchttpclient.providers.netty.response.LazyNettyResponseBodyPart;
import org.asynchttpclient.providers.netty.response.NettyResponseBodyPart;
import org.asynchttpclient.providers.netty.ws.NettyWebSocket;

/**
 * This class can be used to pass Netty's internal configuration options. See
 * Netty documentation for more information.
 */
public class NettyAsyncHttpProviderConfig implements AsyncHttpProviderConfig<ChannelOption<Object>, Object> {

    private final Map<ChannelOption<Object>, Object> properties = new HashMap<ChannelOption<Object>, Object>();

    /**
     * Add a property that will be used when the AsyncHttpClient initialize its
     * {@link org.asynchttpclient.AsyncHttpProvider}
     * 
     * @param name the name of the property
     * @param value the value of the property
     * @return this instance of AsyncHttpProviderConfig
     */
    public NettyAsyncHttpProviderConfig addProperty(ChannelOption<Object> name, Object value) {
        properties.put(name, value);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> NettyAsyncHttpProviderConfig addChannelOption(ChannelOption<T> name, T value) {
        properties.put((ChannelOption<Object>) name, value);
        return this;
    }

    /**
     * Return the value associated with the property's name
     * 
     * @param name
     * @return this instance of AsyncHttpProviderConfig
     */
    public Object getProperty(ChannelOption<Object> name) {
        return properties.get(name);
    }

    /**
     * Remove the value associated with the property's name
     * 
     * @param name
     * @return true if removed
     */
    public Object removeProperty(ChannelOption<Object> name) {
        return properties.remove(name);
    }

    /**
     * Return the curent entry set.
     * 
     * @return a the curent entry set.
     */
    public Set<Map.Entry<ChannelOption<Object>, Object>> propertiesSet() {
        return properties.entrySet();
    }

    public static interface AdditionalChannelInitializer {

        void initChannel(Channel ch) throws Exception;
    }

    public static interface ResponseBodyPartFactory {

        NettyResponseBodyPart newResponseBodyPart(ByteBuf buf, boolean last);
    }

    public static class EagerResponseBodyPartFactory implements ResponseBodyPartFactory {

        @Override
        public NettyResponseBodyPart newResponseBodyPart(ByteBuf buf, boolean last) {
            return new EagerNettyResponseBodyPart(buf, last);
        }
    }

    public static class LazyResponseBodyPartFactory implements ResponseBodyPartFactory {

        @Override
        public NettyResponseBodyPart newResponseBodyPart(ByteBuf buf, boolean last) {
            return new LazyNettyResponseBodyPart(buf, last);
        }
    }

    public static interface NettyWebSocketFactory {
        NettyWebSocket newNettyWebSocket(Channel channel, NettyAsyncHttpProviderConfig nettyConfig);
    }

    public class DefaultNettyWebSocketFactory implements NettyWebSocketFactory {

        @Override
        public NettyWebSocket newNettyWebSocket(Channel channel, NettyAsyncHttpProviderConfig nettyConfig) {
            return new NettyWebSocket(channel, nettyConfig);
        }
    }

    /**
     * Allow configuring the Netty's event loop.
     */
    private EventLoopGroup eventLoopGroup;

    private AdditionalChannelInitializer httpAdditionalChannelInitializer;
    private AdditionalChannelInitializer wsAdditionalChannelInitializer;
    private AdditionalChannelInitializer httpsAdditionalChannelInitializer;
    private AdditionalChannelInitializer wssAdditionalChannelInitializer;

    /**
     * Allow configuring Netty's HttpClientCodecs.
     */
    private int httpClientCodecMaxInitialLineLength = 4096;
    private int httpClientCodecMaxHeaderSize = 8192;
    private int httpClientCodecMaxChunkSize = 8192;

    private ResponseBodyPartFactory bodyPartFactory = new EagerResponseBodyPartFactory();

    private ChannelPool channelPool;

    /**
     * Allow one to disable zero copy for bodies and use chunking instead
     */
    private boolean disableZeroCopy;

    private Timer nettyTimer;

    private long handshakeTimeout;

    private SSLEngineFactory sslEngineFactory;

    /**
     * chunkedFileChunkSize
     */
    private int chunkedFileChunkSize = 8192;

    private NettyWebSocketFactory nettyWebSocketFactory = new DefaultNettyWebSocketFactory();

    private int webSocketMaxBufferSize = 128000000;

    private int webSocketMaxFrameSize = 10 * 1024;

    public EventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }

    public void setEventLoopGroup(EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
    }

    public AdditionalChannelInitializer getHttpAdditionalChannelInitializer() {
        return httpAdditionalChannelInitializer;
    }

    public void setHttpAdditionalChannelInitializer(AdditionalChannelInitializer httpAdditionalChannelInitializer) {
        this.httpAdditionalChannelInitializer = httpAdditionalChannelInitializer;
    }

    public AdditionalChannelInitializer getWsAdditionalChannelInitializer() {
        return wsAdditionalChannelInitializer;
    }

    public void setWsAdditionalChannelInitializer(AdditionalChannelInitializer wsAdditionalChannelInitializer) {
        this.wsAdditionalChannelInitializer = wsAdditionalChannelInitializer;
    }

    public AdditionalChannelInitializer getHttpsAdditionalChannelInitializer() {
        return httpsAdditionalChannelInitializer;
    }

    public void setHttpsAdditionalChannelInitializer(AdditionalChannelInitializer httpsAdditionalChannelInitializer) {
        this.httpsAdditionalChannelInitializer = httpsAdditionalChannelInitializer;
    }

    public AdditionalChannelInitializer getWssAdditionalChannelInitializer() {
        return wssAdditionalChannelInitializer;
    }

    public void setWssAdditionalChannelInitializer(AdditionalChannelInitializer wssAdditionalChannelInitializer) {
        this.wssAdditionalChannelInitializer = wssAdditionalChannelInitializer;
    }

    public int getHttpClientCodecMaxInitialLineLength() {
        return httpClientCodecMaxInitialLineLength;
    }

    public void setHttpClientCodecMaxInitialLineLength(int httpClientCodecMaxInitialLineLength) {
        this.httpClientCodecMaxInitialLineLength = httpClientCodecMaxInitialLineLength;
    }

    public int getHttpClientCodecMaxHeaderSize() {
        return httpClientCodecMaxHeaderSize;
    }

    public void setHttpClientCodecMaxHeaderSize(int httpClientCodecMaxHeaderSize) {
        this.httpClientCodecMaxHeaderSize = httpClientCodecMaxHeaderSize;
    }

    public int getHttpClientCodecMaxChunkSize() {
        return httpClientCodecMaxChunkSize;
    }

    public void setHttpClientCodecMaxChunkSize(int httpClientCodecMaxChunkSize) {
        this.httpClientCodecMaxChunkSize = httpClientCodecMaxChunkSize;
    }

    public ResponseBodyPartFactory getBodyPartFactory() {
        return bodyPartFactory;
    }

    public void setBodyPartFactory(ResponseBodyPartFactory bodyPartFactory) {
        this.bodyPartFactory = bodyPartFactory;
    }

    public ChannelPool getChannelPool() {
        return channelPool;
    }

    public void setChannelPool(ChannelPool channelPool) {
        this.channelPool = channelPool;
    }

    public boolean isDisableZeroCopy() {
        return disableZeroCopy;
    }

    public void setDisableZeroCopy(boolean disableZeroCopy) {
        this.disableZeroCopy = disableZeroCopy;
    }

    public Timer getNettyTimer() {
        return nettyTimer;
    }

    public void setNettyTimer(Timer nettyTimer) {
        this.nettyTimer = nettyTimer;
    }

    public long getHandshakeTimeout() {
        return handshakeTimeout;
    }

    public void setHandshakeTimeout(long handshakeTimeout) {
        this.handshakeTimeout = handshakeTimeout;
    }

    public SSLEngineFactory getSslEngineFactory() {
        return sslEngineFactory;
    }

    public void setSslEngineFactory(SSLEngineFactory sslEngineFactory) {
        this.sslEngineFactory = sslEngineFactory;
    }

    public int getChunkedFileChunkSize() {
        return chunkedFileChunkSize;
    }

    public void setChunkedFileChunkSize(int chunkedFileChunkSize) {
        this.chunkedFileChunkSize = chunkedFileChunkSize;
    }

    public NettyWebSocketFactory getNettyWebSocketFactory() {
        return nettyWebSocketFactory;
    }

    public void setNettyWebSocketFactory(NettyWebSocketFactory nettyWebSocketFactory) {
        this.nettyWebSocketFactory = nettyWebSocketFactory;
    }

    public int getWebSocketMaxBufferSize() {
        return webSocketMaxBufferSize;
    }

    public void setWebSocketMaxBufferSize(int webSocketMaxBufferSize) {
        this.webSocketMaxBufferSize = webSocketMaxBufferSize;
    }

    public int getWebSocketMaxFrameSize() {
        return webSocketMaxFrameSize;
    }
    public void setWebSocketMaxFrameSize(int webSocketMaxFrameSize) {
        this.webSocketMaxFrameSize = webSocketMaxFrameSize;
    }
}
