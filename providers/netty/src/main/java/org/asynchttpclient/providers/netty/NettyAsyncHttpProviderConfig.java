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

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.util.HashedWheelTimer;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.asynchttpclient.AsyncHttpProviderConfig;
import org.asynchttpclient.providers.netty.channel.ChannelPool;
import org.asynchttpclient.providers.netty.response.EagerResponseBodyPart;
import org.asynchttpclient.providers.netty.response.LazyResponseBodyPart;
import org.asynchttpclient.providers.netty.response.NettyResponseBodyPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class can be used to pass Netty's internal configuration options. See Netty documentation for more information.
 */
public class NettyAsyncHttpProviderConfig implements AsyncHttpProviderConfig<String, Object> {

    private final static Logger LOGGER = LoggerFactory.getLogger(NettyAsyncHttpProviderConfig.class);

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

    /**
     * Use direct {@link java.nio.ByteBuffer}
     */
    public final static String USE_DIRECT_BYTEBUFFER = "bufferFactory";

    /**
     * Allow nested request from any {@link org.asynchttpclient.AsyncHandler}
     */
    public final static String DISABLE_NESTED_REQUEST = "disableNestedRequest";

    /**
     * See {@link java.net.Socket#setReuseAddress(boolean)}
     */
    public final static String REUSE_ADDRESS = ChannelOption.SO_REUSEADDR.name();

    private final Map<String, Object> properties = new HashMap<String, Object>();

    private ResponseBodyPartFactory bodyPartFactory = new EagerResponseBodyPartFactory();

    private ChannelPool channelPool;

    private boolean disableZeroCopy;

    private HashedWheelTimer hashedWheelTimer;

    private long handshakeTimeoutInMillis;

    public NettyAsyncHttpProviderConfig() {
        properties.put(REUSE_ADDRESS, Boolean.FALSE);
    }

    /**
     * Add a property that will be used when the AsyncHttpClient initialize its {@link org.asynchttpclient.AsyncHttpProvider}
     * 
     * @param name
     *            the name of the property
     * @param value
     *            the value of the property
     * @return this instance of AsyncHttpProviderConfig
     */
    public NettyAsyncHttpProviderConfig addProperty(String name, Object value) {

        if (name.equals(REUSE_ADDRESS) && value == Boolean.TRUE && System.getProperty("os.name").toLowerCase().contains("win")) {
            LOGGER.warn("Can't enable {} on Windows", REUSE_ADDRESS);
        } else {
            properties.put(name, value);
        }

        return this;
    }

    /**
     * Return the value associated with the property's name
     * 
     * @param name
     * @return this instance of AsyncHttpProviderConfig
     */
    public Object getProperty(String name) {
        return properties.get(name);
    }

    /**
     * Remove the value associated with the property's name
     * 
     * @param name
     * @return true if removed
     */
    public Object removeProperty(String name) {
        return properties.remove(name);
    }

    /**
     * Return the curent entry set.
     * 
     * @return a the curent entry set.
     */
    public Set<Map.Entry<String, Object>> propertiesSet() {
        return properties.entrySet();
    }

    public EventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }

    public void setEventLoopGroup(EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
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

    public HashedWheelTimer getHashedWheelTimer() {
        return hashedWheelTimer;
    }

    public void setHashedWheelTimer(HashedWheelTimer hashedWheelTimer) {
        this.hashedWheelTimer = hashedWheelTimer;
    }

    public long getHandshakeTimeoutInMillis() {
        return handshakeTimeoutInMillis;
    }

    public void setHandshakeTimeoutInMillis(long handshakeTimeoutInMillis) {
        this.handshakeTimeoutInMillis = handshakeTimeoutInMillis;
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
}
