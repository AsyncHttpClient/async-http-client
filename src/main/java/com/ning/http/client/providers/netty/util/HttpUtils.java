/*
 * Copyright (c) 2014-2015 AsyncHttpClient Project. All rights reserved.
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
package com.ning.http.client.providers.netty.util;

import org.jboss.netty.handler.codec.http.HttpHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

public final class HttpUtils {
    private HttpUtils() {
    }

    public static List<String> getNettyHeaderValuesByCaseInsensitiveName(HttpHeaders headers, String name) {
        ArrayList<String> l = new ArrayList<>();
        for (Entry<String, String> e : headers) {
            if (e.getKey().equalsIgnoreCase(name)) {
                l.add(e.getValue().trim());
            }
        }
        return l;
    }
}
