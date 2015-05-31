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
package org.asynchttpclient.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.Timer;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncHttpProviderConfig;
import org.asynchttpclient.channel.pool.ConnectionStrategy;
import org.asynchttpclient.netty.channel.pool.ChannelPool;
import org.asynchttpclient.netty.handler.Http1Point1ConnectionStrategy;
import org.asynchttpclient.netty.ws.NettyWebSocket;

/**
 * This class can be used to pass Netty's internal configuration options. See
 * Netty documentation for more information.
 */
public class NettyAsyncHttpProviderConfig implements AsyncHttpProviderConfig<ChannelOption<Object>, Object> {

    private final Map<ChannelOption<Object>, Object> properties = new HashMap<>();

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

    public static interface AdditionalPipelineInitializer {

        void initPipeline(ChannelPipeline pipeline) throws Exception;
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
        NettyWebSocket newNettyWebSocket(Channel channel, AsyncHttpClientConfig config);
    }

    public class DefaultNettyWebSocketFactory implements NettyWebSocketFactory {

        @Override
        public NettyWebSocket newNettyWebSocket(Channel channel, AsyncHttpClientConfig config) {
            return new NettyWebSocket(channel, config);
        }
    }

    /**
     * Allow configuring the Netty's event loop.
     */
    private EventLoopGroup eventLoopGroup;

    private AdditionalPipelineInitializer httpAdditionalPipelineInitializer;
    private AdditionalPipelineInitializer wsAdditionalPipelineInitializer;
    private AdditionalPipelineInitializer httpsAdditionalPipelineInitializer;
    private AdditionalPipelineInitializer wssAdditionalPipelineInitializer;

    private ResponseBodyPartFactory bodyPartFactory = new EagerResponseBodyPartFactory();

    private ChannelPool channelPool;

    private Timer nettyTimer;

    private NettyWebSocketFactory nettyWebSocketFactory = new DefaultNettyWebSocketFactory();

    private ConnectionStrategy<HttpRequest, HttpResponse> connectionStrategy = new Http1Point1ConnectionStrategy();

    public EventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }

    public void setEventLoopGroup(EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
    }

    public AdditionalPipelineInitializer getHttpAdditionalPipelineInitializer() {
        return httpAdditionalPipelineInitializer;
    }

    public void setHttpAdditionalPipelineInitializer(AdditionalPipelineInitializer httpAdditionalPipelineInitializer) {
        this.httpAdditionalPipelineInitializer = httpAdditionalPipelineInitializer;
    }

    public AdditionalPipelineInitializer getWsAdditionalPipelineInitializer() {
        return wsAdditionalPipelineInitializer;
    }

    public void setWsAdditionalPipelineInitializer(AdditionalPipelineInitializer wsAdditionalPipelineInitializer) {
        this.wsAdditionalPipelineInitializer = wsAdditionalPipelineInitializer;
    }

    public AdditionalPipelineInitializer getHttpsAdditionalPipelineInitializer() {
        return httpsAdditionalPipelineInitializer;
    }

    public void setHttpsAdditionalPipelineInitializer(AdditionalPipelineInitializer httpsAdditionalPipelineInitializer) {
        this.httpsAdditionalPipelineInitializer = httpsAdditionalPipelineInitializer;
    }

    public AdditionalPipelineInitializer getWssAdditionalPipelineInitializer() {
        return wssAdditionalPipelineInitializer;
    }

    public void setWssAdditionalPipelineInitializer(AdditionalPipelineInitializer wssAdditionalPipelineInitializer) {
        this.wssAdditionalPipelineInitializer = wssAdditionalPipelineInitializer;
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

    public Timer getNettyTimer() {
        return nettyTimer;
    }

    public void setNettyTimer(Timer nettyTimer) {
        this.nettyTimer = nettyTimer;
    }

    public NettyWebSocketFactory getNettyWebSocketFactory() {
        return nettyWebSocketFactory;
    }

    public void setNettyWebSocketFactory(NettyWebSocketFactory nettyWebSocketFactory) {
        this.nettyWebSocketFactory = nettyWebSocketFactory;
    }

    public ConnectionStrategy<HttpRequest, HttpResponse> getConnectionStrategy() {
        return connectionStrategy;
    }

    public void setConnectionStrategy(ConnectionStrategy<HttpRequest, HttpResponse> connectionStrategy) {
        this.connectionStrategy = connectionStrategy;
    }
}
