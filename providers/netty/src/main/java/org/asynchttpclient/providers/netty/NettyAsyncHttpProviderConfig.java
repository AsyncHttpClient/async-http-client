/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 */
package org.asynchttpclient.providers.netty;

import org.asynchttpclient.AsyncHttpProviderConfig;
import org.asynchttpclient.SSLEngineFactory;
import org.asynchttpclient.providers.netty.channel.pool.ChannelPool;
import org.asynchttpclient.providers.netty.response.EagerResponseBodyPart;
import org.asynchttpclient.providers.netty.response.LazyResponseBodyPart;
import org.asynchttpclient.providers.netty.response.NettyResponseBodyPart;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Timer;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * This class can be used to pass Netty's internal configuration options. See Netty documentation for more information.
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
            return new EagerResponseBodyPart(buf, last);
        }
    }

    public static class LazyResponseBodyPartFactory implements ResponseBodyPartFactory {

        @Override
        public NettyResponseBodyPart newResponseBodyPart(ByteBuf buf, boolean last) {
            return new LazyResponseBodyPart(buf, last);
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
     * HttpClientCodec's maxInitialLineLength
     */
    private int maxInitialLineLength = 4096;

    /**
     * HttpClientCodec's maxHeaderSize
     */
    private int maxHeaderSize = 8192;

    /**
     * HttpClientCodec's maxChunkSize
     */
    private int maxChunkSize = 8192;

    private ResponseBodyPartFactory bodyPartFactory = new EagerResponseBodyPartFactory();

    private ChannelPool channelPool;

    private boolean disableZeroCopy;

    private Timer nettyTimer;

    private long handshakeTimeoutInMillis;

    private SSLEngineFactory sslEngineFactory;

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

    public int getMaxInitialLineLength() {
        return maxInitialLineLength;
    }

    public void setMaxInitialLineLength(int maxInitialLineLength) {
        this.maxInitialLineLength = maxInitialLineLength;
    }

    public int getMaxHeaderSize() {
        return maxHeaderSize;
    }

    public void setMaxHeaderSize(int maxHeaderSize) {
        this.maxHeaderSize = maxHeaderSize;
    }

    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    public void setMaxChunkSize(int maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
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

    public long getHandshakeTimeoutInMillis() {
        return handshakeTimeoutInMillis;
    }

    public void setHandshakeTimeoutInMillis(long handshakeTimeoutInMillis) {
        this.handshakeTimeoutInMillis = handshakeTimeoutInMillis;
    }

    public SSLEngineFactory getSslEngineFactory() {
        return sslEngineFactory;
    }

    public void setSslEngineFactory(SSLEngineFactory sslEngineFactory) {
        this.sslEngineFactory = sslEngineFactory;
    }
}
