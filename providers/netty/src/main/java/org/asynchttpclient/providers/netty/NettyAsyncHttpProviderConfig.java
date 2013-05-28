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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.asynchttpclient.AsyncHttpProviderConfig;

/**
 * This class can be used to pass Netty's internal configuration options. See Netty documentation for more information.
 */
public class NettyAsyncHttpProviderConfig implements AsyncHttpProviderConfig<String, Object> {

    private final static Logger LOGGER = LoggerFactory.getLogger(NettyAsyncHttpProviderConfig.class);

    /**
     * Use Netty's blocking IO stategy.
     */
    private boolean useBlockingIO;

    /**
     * Allow configuring the Netty's socket channel factory.
     */
    private NioClientSocketChannelFactory socketChannelFactory;

    /**
     * Allow configuring the Netty's boss executor service.
     */
    private ExecutorService bossExecutorService;

    /**
     * Execute the connect operation asynchronously.
     */
    private boolean asyncConnect;

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
    public final static String REUSE_ADDRESS = "reuseAddress";

    private final Map<String, Object> properties = new HashMap<String, Object>();

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

    public boolean isUseBlockingIO() {
        return useBlockingIO;
    }

    public void setUseBlockingIO(boolean useBlockingIO) {
        this.useBlockingIO = useBlockingIO;
    }

    public NioClientSocketChannelFactory getSocketChannelFactory() {
        return socketChannelFactory;
    }

    public void setSocketChannelFactory(NioClientSocketChannelFactory socketChannelFactory) {
        this.socketChannelFactory = socketChannelFactory;
    }

    public ExecutorService getBossExecutorService() {
        return bossExecutorService;
    }

    public void setBossExecutorService(ExecutorService bossExecutorService) {
        this.bossExecutorService = bossExecutorService;
    }

    public boolean isAsyncConnect() {
        return asyncConnect;
    }

    public void setAsyncConnect(boolean asyncConnect) {
        this.asyncConnect = asyncConnect;
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
}
