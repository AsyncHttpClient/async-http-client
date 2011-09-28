/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.ning.http.client.providers.grizzly;

import com.ning.http.client.AsyncHttpProviderConfig;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
        TRANSPORT_CUSTOMIZER(TransportCustomizer.class);
        
        
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
    
    // ------------------------------------ Methods from AsyncHttpProviderConfig

    /**
     * {@inheritDoc}
     * 
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

    /**
     * {@inheritDoc}
     */
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

    /**
      * {@inheritDoc}
      */
    @Override
    public Object removeProperty(Property name) {
        if (name == null) {
            return null;
        }
        return attributes.remove(name);
    }

    /**
      * {@inheritDoc}
      */
    @Override
    public Set<Map.Entry<Property,Object>> propertiesSet() {
        return attributes.entrySet();
    }

}
