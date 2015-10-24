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
package org.asynchttpclient;

import static org.asynchttpclient.util.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Timer;

import java.util.HashMap;
import java.util.Map;

import org.asynchttpclient.channel.pool.KeepAliveStrategy;
import org.asynchttpclient.netty.EagerNettyResponseBodyPart;
import org.asynchttpclient.netty.LazyNettyResponseBodyPart;
import org.asynchttpclient.netty.NettyResponseBodyPart;
import org.asynchttpclient.netty.channel.pool.ChannelPool;
import org.asynchttpclient.netty.ws.NettyWebSocket;

public class AdvancedConfig {

    private final Map<ChannelOption<Object>, Object> channelOptions;
    private final EventLoopGroup eventLoopGroup;
    private final boolean preferNative;
    private final AdditionalPipelineInitializer httpAdditionalPipelineInitializer;
    private final AdditionalPipelineInitializer wsAdditionalPipelineInitializer;
    private final ResponseBodyPartFactory responseBodyPartFactory;
    private final ChannelPool channelPool;
    private final Timer nettyTimer;
    private final NettyWebSocketFactory nettyWebSocketFactory;
    private final KeepAliveStrategy connectionStrategy;

    public AdvancedConfig(//
            Map<ChannelOption<Object>, Object> channelOptions,//
            EventLoopGroup eventLoopGroup,//
            boolean preferNative,//
            AdditionalPipelineInitializer httpAdditionalPipelineInitializer,//
            AdditionalPipelineInitializer wsAdditionalPipelineInitializer,//
            ResponseBodyPartFactory responseBodyPartFactory,//
            ChannelPool channelPool,//
            Timer nettyTimer,//
            NettyWebSocketFactory nettyWebSocketFactory,//
            KeepAliveStrategy connectionStrategy) {

        assertNotNull(responseBodyPartFactory, "responseBodyPartFactory");
        assertNotNull(nettyWebSocketFactory, "nettyWebSocketFactory");
        assertNotNull(connectionStrategy, "connectionStrategy");

        this.channelOptions = channelOptions;
        this.eventLoopGroup = eventLoopGroup;
        this.preferNative = preferNative;
        this.httpAdditionalPipelineInitializer = httpAdditionalPipelineInitializer;
        this.wsAdditionalPipelineInitializer = wsAdditionalPipelineInitializer;
        this.responseBodyPartFactory = responseBodyPartFactory;
        this.channelPool = channelPool;
        this.nettyTimer = nettyTimer;
        this.nettyWebSocketFactory = nettyWebSocketFactory;
        this.connectionStrategy = connectionStrategy;
    }

    public Map<ChannelOption<Object>, Object> getChannelOptions() {
        return channelOptions;
    }

    public EventLoopGroup getEventLoopGroup() {
        return eventLoopGroup;
    }

    public boolean isPreferNative() {
        return preferNative;
    }

    public AdditionalPipelineInitializer getHttpAdditionalPipelineInitializer() {
        return httpAdditionalPipelineInitializer;
    }

    public AdditionalPipelineInitializer getWsAdditionalPipelineInitializer() {
        return wsAdditionalPipelineInitializer;
    }

    public ResponseBodyPartFactory getResponseBodyPartFactory() {
        return responseBodyPartFactory;
    }

    public ChannelPool getChannelPool() {
        return channelPool;
    }

    public Timer getNettyTimer() {
        return nettyTimer;
    }

    public NettyWebSocketFactory getNettyWebSocketFactory() {
        return nettyWebSocketFactory;
    }

    public KeepAliveStrategy getKeepAliveStrategy() {
        return connectionStrategy;
    }

    public static class Builder {

        private Map<ChannelOption<Object>, Object> channelOptions = new HashMap<>();
        private EventLoopGroup eventLoopGroup;
        private boolean preferNative;
        private AdditionalPipelineInitializer httpAdditionalPipelineInitializer;
        private AdditionalPipelineInitializer wsAdditionalPipelineInitializer;
        private ResponseBodyPartFactory responseBodyPartFactory = new EagerResponseBodyPartFactory();
        private ChannelPool channelPool;
        private Timer nettyTimer;
        private NettyWebSocketFactory nettyWebSocketFactory = new DefaultNettyWebSocketFactory();
        private KeepAliveStrategy keepAliveStrategy = KeepAliveStrategy.DefaultKeepAliveStrategy.INSTANCE;

        /**
         * @param name the name of the ChannelOption
         * @param value the value of the ChannelOption
         * @param <T> the type of value
         * @return this instance of AdvancedConfig
         */
        @SuppressWarnings("unchecked")
        public <T> Builder addChannelOption(ChannelOption<T> name, T value) {
            channelOptions.put((ChannelOption<Object>) name, value);
            return this;
        }

        public Builder setEventLoopGroup(EventLoopGroup eventLoopGroup) {
            this.eventLoopGroup = eventLoopGroup;
            return this;
        }

        public Builder setPreferNative(boolean preferNative) {
            this.preferNative = preferNative;
            return this;
        }

        public Builder setHttpAdditionalPipelineInitializer(AdditionalPipelineInitializer httpAdditionalPipelineInitializer) {
            this.httpAdditionalPipelineInitializer = httpAdditionalPipelineInitializer;
            return this;
        }

        public Builder setWsAdditionalPipelineInitializer(AdditionalPipelineInitializer wsAdditionalPipelineInitializer) {
            this.wsAdditionalPipelineInitializer = wsAdditionalPipelineInitializer;
            return this;
        }

        public Builder setResponseBodyPartFactory(ResponseBodyPartFactory responseBodyPartFactory) {
            this.responseBodyPartFactory = responseBodyPartFactory;
            return this;
        }

        public Builder setChannelPool(ChannelPool channelPool) {
            this.channelPool = channelPool;
            return this;
        }

        public Builder setNettyTimer(Timer nettyTimer) {
            this.nettyTimer = nettyTimer;
            return this;
        }

        public Builder setNettyWebSocketFactory(NettyWebSocketFactory nettyWebSocketFactory) {
            this.nettyWebSocketFactory = nettyWebSocketFactory;
            return this;
        }

        public Builder setKeepAliveStrategy(KeepAliveStrategy keepAliveStrategy) {
            this.keepAliveStrategy = keepAliveStrategy;
            return this;
        }

        public AdvancedConfig build() {
            return new AdvancedConfig(//
                    channelOptions,//
                    eventLoopGroup,//
                    preferNative,//
                    httpAdditionalPipelineInitializer,//
                    wsAdditionalPipelineInitializer,//
                    responseBodyPartFactory,//
                    channelPool,//
                    nettyTimer,//
                    nettyWebSocketFactory,//
                    keepAliveStrategy);
        }
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

    public static class DefaultNettyWebSocketFactory implements NettyWebSocketFactory {

        @Override
        public NettyWebSocket newNettyWebSocket(Channel channel, AsyncHttpClientConfig config) {
            return new NettyWebSocket(channel, config);
        }
    }
}
