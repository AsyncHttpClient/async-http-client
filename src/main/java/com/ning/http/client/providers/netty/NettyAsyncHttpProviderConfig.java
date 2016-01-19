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
package com.ning.http.client.providers.netty;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.util.Timer;

import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.SSLEngineFactory;
import com.ning.http.client.providers.netty.channel.pool.ChannelPool;
import com.ning.http.client.providers.netty.handler.ConnectionStrategy;
import com.ning.http.client.providers.netty.handler.DefaultConnectionStrategy;
import com.ning.http.client.providers.netty.ws.NettyWebSocket;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * This class can be used to pass Netty's internal configuration options. See Netty documentation for more information.
 */
public class NettyAsyncHttpProviderConfig implements AsyncHttpProviderConfig<String, Object> {

    private final ConcurrentHashMap<String, Object> properties = new ConcurrentHashMap<>();

    /**
     * Add a property that will be used when the AsyncHttpClient initialize its {@link com.ning.http.client.AsyncHttpProvider}
     * 
     * @param name
     *            the name of the property
     * @param value
     *            the value of the property
     * @return this instance of AsyncHttpProviderConfig
     */
    public NettyAsyncHttpProviderConfig addProperty(String name, Object value) {
        properties.put(name, value);
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
     * Return the value associated with the property's name
     * 
     * @param name
     * @return this instance of AsyncHttpProviderConfig
     */
    public <T> T getProperty(String name, Class<T> type, T defaultValue) {
        Object value = properties.get(name);
        if (value != null && type.isAssignableFrom(value.getClass())) {
            return type.cast(value);
        }
        return defaultValue;
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

    /**
     * Enable Netty DeadLockChecker
     */
    private boolean useDeadLockChecker = true;

    /**
     * Allow configuring the Netty's boss executor service.
     */
    private ExecutorService bossExecutorService;

    private AdditionalPipelineInitializer httpAdditionalPipelineInitializer;
    private AdditionalPipelineInitializer wsAdditionalPipelineInitializer;
    private AdditionalPipelineInitializer httpsAdditionalPipelineInitializer;
    private AdditionalPipelineInitializer wssAdditionalPipelineInitializer;

    /**
     * Allow configuring Netty's HttpClientCodecs.
     */
    private int httpClientCodecMaxInitialLineLength = 4096;
    private int httpClientCodecMaxHeaderSize = 8192;
    private int httpClientCodecMaxChunkSize = 8192;

    /**
     * Allow configuring the Netty's socket channel factory.
     */
    private NioClientSocketChannelFactory socketChannelFactory;

    private ChannelPool channelPool;

    /**
     * Allow one to disable zero copy for bodies and use chunking instead
     */
    private boolean disableZeroCopy;

    private Timer nettyTimer;

    private long handshakeTimeout = 10000L;

    private SSLEngineFactory sslEngineFactory;

    /**
     * chunkedFileChunkSize
     */
    private int chunkedFileChunkSize = 8192;

    private NettyWebSocketFactory nettyWebSocketFactory = new DefaultNettyWebSocketFactory();

    private int webSocketMaxBufferSize = 128000000;

    private int webSocketMaxFrameSize = 10 * 1024;

    private boolean keepEncodingHeader = false;

    private ConnectionStrategy connectionStrategy = new DefaultConnectionStrategy();

    public boolean isUseDeadLockChecker() {
        return useDeadLockChecker;
    }

    public void setUseDeadLockChecker(boolean useDeadLockChecker) {
        this.useDeadLockChecker = useDeadLockChecker;
    }

    public ExecutorService getBossExecutorService() {
        return bossExecutorService;
    }

    public void setBossExecutorService(ExecutorService bossExecutorService) {
        this.bossExecutorService = bossExecutorService;
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

    public NioClientSocketChannelFactory getSocketChannelFactory() {
        return socketChannelFactory;
    }

    public void setSocketChannelFactory(NioClientSocketChannelFactory socketChannelFactory) {
        this.socketChannelFactory = socketChannelFactory;
    }

    public void setDisableZeroCopy(boolean disableZeroCopy) {
        this.disableZeroCopy = disableZeroCopy;
    }

    public boolean isDisableZeroCopy() {
        return disableZeroCopy;
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

    public ChannelPool getChannelPool() {
        return channelPool;
    }

    public void setChannelPool(ChannelPool channelPool) {
        this.channelPool = channelPool;
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

    public boolean isKeepEncodingHeader() {
        return keepEncodingHeader;
    }

    public void setKeepEncodingHeader(boolean keepEncodingHeader) {
        this.keepEncodingHeader = keepEncodingHeader;
    }

    public ConnectionStrategy getConnectionStrategy() {
        return connectionStrategy;
    }

    public void setConnectionStrategy(ConnectionStrategy connectionStrategy) {
        this.connectionStrategy = connectionStrategy;
    }

    public static interface NettyWebSocketFactory {
        NettyWebSocket newNettyWebSocket(Channel channel, NettyAsyncHttpProviderConfig nettyConfig);
    }

    public static interface AdditionalPipelineInitializer {

        void initPipeline(ChannelPipeline pipeline) throws Exception;
    }

    public class DefaultNettyWebSocketFactory implements NettyWebSocketFactory {

        @Override
        public NettyWebSocket newNettyWebSocket(Channel channel, NettyAsyncHttpProviderConfig nettyConfig) {
            return new NettyWebSocket(channel, nettyConfig);
        }
    }
}
