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
     * @return
     */
    public AsyncHttpProviderConfig addProperty(U name, V value);

    /**
     * Return the value associated with the property's name
     *
     * @param name
     * @return
     */
    public V getProperty(U name);

    /**
     * Remove the value associated with the property's name
     *
     * @param name
     * @return
     */
    public V removeProperty(U name);

    /**
     * Return the curent entry set.
     *
     * @return a the curent entry set.
     */
    public Set<Map.Entry<U, V>> propertiesSet();
}
