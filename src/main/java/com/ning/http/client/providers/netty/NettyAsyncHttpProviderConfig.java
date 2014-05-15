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
package com.ning.http.client.providers.netty;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.netty.util.Timer;

import com.ning.http.client.AsyncHttpProviderConfig;

/**
 * This class can be used to pass Netty's internal configuration options. See Netty documentation for more information.
 */
public class NettyAsyncHttpProviderConfig implements AsyncHttpProviderConfig<String, Object> {

    /**
     * Use Netty's blocking IO stategy.
     */
    public final static String USE_BLOCKING_IO = "useBlockingIO";

    /**
     * Use direct {@link java.nio.ByteBuffer}
     */
    public final static String USE_DIRECT_BYTEBUFFER = "bufferFactory";

    /**
     * Execute the connect operation asynchronously.
     */
    public final static String EXECUTE_ASYNC_CONNECT = "asyncConnect";

    /**
     * Allow nested request from any {@link com.ning.http.client.AsyncHandler}
     */
    public final static String DISABLE_NESTED_REQUEST = "disableNestedRequest";

    /**
     * Allow configuring the Netty's boss executor service.
     */
    public final static String BOSS_EXECUTOR_SERVICE = "bossExecutorService";

    /**
     * See {@link java.net.Socket#setReuseAddress(boolean)}
     */
    public final static String REUSE_ADDRESS = "reuseAddress";

    /**
     * Allow configuring the Netty's HttpClientCodec.
     */
    public final static String HTTP_CLIENT_CODEC_MAX_INITIAL_LINE_LENGTH = "httpClientCodecMaxInitialLineLength";
    public final static String HTTP_CLIENT_CODEC_MAX_HEADER_SIZE = "httpClientCodecMaxHeaderSize";
    public final static String HTTP_CLIENT_CODEC_MAX_CHUNK_SIZE = "httpClientCodecMaxChunkSize";

    /**
     * Allow configuring the Netty's HttpClientCodec.
     */
    public final static String HTTPS_CLIENT_CODEC_MAX_INITIAL_LINE_LENGTH = "httpsClientCodecMaxInitialLineLength";
    public final static String HTTPS_CLIENT_CODEC_MAX_HEADER_SIZE = "httpsClientCodecMaxHeaderSize";
    public final static String HTTPS_CLIENT_CODEC_MAX_CHUNK_SIZE = "httpsClientCodecMaxChunkSize";

    /**
     * Allow configuring the Netty's socket channel factory.
     */
    public final static String SOCKET_CHANNEL_FACTORY = "socketChannelFactory";

    private final ConcurrentHashMap<String, Object> properties = new ConcurrentHashMap<String, Object>();

    /**
     * Allow one to disable zero copy for bodies and use chunking instead;
     */
    private boolean disableZeroCopy;

    private Timer nettyTimer;
    
    private long handshakeTimeoutInMillis = 10000L;

    public NettyAsyncHttpProviderConfig() {
        properties.put(REUSE_ADDRESS, "false");
    }

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

    public long getHandshakeTimeoutInMillis() {
        return handshakeTimeoutInMillis;
    }

    public void setHandshakeTimeoutInMillis(long handshakeTimeoutInMillis) {
        this.handshakeTimeoutInMillis = handshakeTimeoutInMillis;
    }
}
