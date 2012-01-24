/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package com.ning.http.client;

import java.util.Map;
import java.util.Set;

/**
 * {@link com.ning.http.client.AsyncHttpProvider} proprietary configurable properties. Note that properties are
 * <strong>AsyncHttpProvider</strong> dependent, so make sure you consult the AsyncHttpProvider's documentation
 * about what is supported and what's not.
 */
public interface AsyncHttpProviderConfig<U, V> {

    /**
     * Add a property that will be used when the AsyncHttpClient initialize its {@link com.ning.http.client.AsyncHttpProvider}
     *
     * @param name  the name of the property
     * @param value the value of the property
     * @return this instance of AsyncHttpProviderConfig
     */
    public AsyncHttpProviderConfig addProperty(U name, V value);

    /**
     * Return the value associated with the property's name
     *
     * @param name
     * @return this instance of AsyncHttpProviderConfig
     */
    public V getProperty(U name);

    /**
     * Remove the value associated with the property's name
     *
     * @param name
     * @return true if removed
     */
    public V removeProperty(U name);

    /**
     * Return the curent entry set.
     *
     * @return a the curent entry set.
     */
    public Set<Map.Entry<U, V>> propertiesSet();
}
