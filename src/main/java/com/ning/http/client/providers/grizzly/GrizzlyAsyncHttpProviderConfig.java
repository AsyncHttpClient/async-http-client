/*
 * Copyright (c) 2012-2015 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package com.ning.http.client.providers.grizzly;

import com.ning.http.client.AsyncHttpProviderConfig;
import com.ning.http.client.SSLEngineFactory;
import java.net.SocketAddress;

import org.glassfish.grizzly.http.HttpCodecFilter;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.glassfish.grizzly.connectionpool.MultiEndpointPool;

/**
 * {@link AsyncHttpProviderConfig} implementation that allows customization
 * of the Grizzly runtime outside of the scope of what the
 * {@link com.ning.http.client.AsyncHttpClientConfig} offers.
 *
 * @see Property
 * 
 * @author The Grizzly Team
 * @since 1.7.0
 */
public class GrizzlyAsyncHttpProviderConfig implements AsyncHttpProviderConfig<GrizzlyAsyncHttpProviderConfig.Property,Object> {

    /**
     * Grizzly-specific customization properties.  Each property describes
     * what it's used for, what the default value is (if any), and what
     * the expected type the value of the property should be.
     */
    public static enum Property {

        /**
         * If this property is specified with a custom {@link TransportCustomizer}
         * instance, the {@link TCPNIOTransport} instance that is created by
         * {@link GrizzlyAsyncHttpProvider} will be passed to the customizer
         * bypassing all default configuration of the transport typically performed
         * by the provider. The type of the value associated with this property
         * must be <code>TransportCustomizer.class</code>.
         *
         * @see TransportCustomizer
         */
        TRANSPORT_CUSTOMIZER(TransportCustomizer.class),


        /**
         * Defines the maximum HTTP packet header size.
         */
        MAX_HTTP_PACKET_HEADER_SIZE(Integer.class, HttpCodecFilter.DEFAULT_MAX_HTTP_PACKET_HEADER_SIZE),


        /**
         * By default, Websocket messages that are fragmented will be buffered.  Once all
         * fragments have been accumulated, the appropriate onMessage() call back will be
         * invoked with the complete message.  If this functionality is not desired, set
         * this property to false.
         */
        BUFFER_WEBSOCKET_FRAGMENTS(Boolean.class, true),

        /**
         * <tt>true</tt> (default), if an HTTP response has to be decompressed
         * (if compressed by a server), or <tt>false</tt> if decompression
         * has to be delegated to a user.
         */
        DECOMPRESS_RESPONSE(Boolean.class, true)
        
        ;
        
        
        final Object defaultValue;
        final Class<?> type;
        
        private Property(final Class<?> type, final Object defaultValue) {
            this.type = type;
            this.defaultValue = defaultValue;
        }
        
        private Property(final Class<?> type) {
            this(type, null);
        }
        
        boolean hasDefaultValue() {
            return (defaultValue != null);
        }
        
        
    } // END PROPERTY
    
    private final Map<Property,Object> attributes = new HashMap<Property,Object>();

    protected MultiEndpointPool<SocketAddress> connectionPool;

    private SSLEngineFactory sslEngineFactory;
    
    // ------------------------------------ Methods from AsyncHttpProviderConfig

    /**
     * @throws IllegalArgumentException if the type of the specified value
     *  does not match the expected type of the specified {@link Property}.
     */
    @Override
    public AsyncHttpProviderConfig addProperty(Property name, Object value) {
        if (name == null) {
            return this;
        }
        if (value == null) {
            if (name.hasDefaultValue()) {
                value = name.defaultValue;
            } else {
                return this;
            }
        } else {
            if (!name.type.isAssignableFrom(value.getClass())) {
                throw new IllegalArgumentException(
                        String.format(
                                "The value of property [%s] must be of type [%s].  Type of value provided: [%s].",
                                name.name(),
                                name.type.getName(),
                                value.getClass().getName()));
            }
        }
        attributes.put(name, value);
        return this;
    }

    @Override
    public Object getProperty(Property name) {
        Object ret = attributes.get(name);
        if (ret == null) {
            if (name.hasDefaultValue()) {
                ret = name.defaultValue;
            }
        }
        return ret;
    }

    @Override
    public Object removeProperty(Property name) {
        if (name == null) {
            return null;
        }
        return attributes.remove(name);
    }

    @Override
    public Set<Map.Entry<Property,Object>> propertiesSet() {
        return attributes.entrySet();
    }

    public MultiEndpointPool<SocketAddress> getConnectionPool() {
        return connectionPool;
    }

    public void setConnectionPool(MultiEndpointPool<SocketAddress> connectionPool) {
        this.connectionPool = connectionPool;
    }
    
    public SSLEngineFactory getSslEngineFactory() {
        return sslEngineFactory;
    }

    public void setSslEngineFactory(SSLEngineFactory sslEngineFactory) {
        this.sslEngineFactory = sslEngineFactory;
    }
}
