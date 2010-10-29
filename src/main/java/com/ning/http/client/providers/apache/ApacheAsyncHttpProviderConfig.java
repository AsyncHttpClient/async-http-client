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
package com.ning.http.client.providers.apache;

import com.ning.http.client.AsyncHttpProviderConfig;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ApacheAsyncHttpProviderConfig implements AsyncHttpProviderConfig<String,String> {

    private final ConcurrentHashMap<String, String> properties = new ConcurrentHashMap<String, String>();


    public AsyncHttpProviderConfig addProperty(String name, String value) {
        properties.put(name, value);
        return this;
    }

    public String getProperty(String name) {
        return properties.get(name);
    }

    public String removeProperty(String name) {
        return properties.remove(name);
    }

    public Set<Map.Entry<String, String>> propertiesSet() {
        return properties.entrySet();
    }
}
