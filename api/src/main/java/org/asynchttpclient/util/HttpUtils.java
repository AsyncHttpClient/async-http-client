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
package org.asynchttpclient.util;

import org.asynchttpclient.uri.Uri;

public final class HttpUtils {

    public static final String HTTP = "http";
    public static final String HTTPS = "https";
    public static final String WS = "ws";
    public static final String WSS = "wss";

    private HttpUtils() {
    }

    public static boolean isWebSocket(String scheme) {
        return WS.equals(scheme) || WSS.equalsIgnoreCase(scheme);
    }

    public static boolean isSecure(String scheme) {
        return HTTPS.equals(scheme) || WSS.equals(scheme);
    }

    public static boolean isSecure(Uri uri) {
        return isSecure(uri.getScheme());
    }

    public static boolean useProxyConnect(Uri uri) {
        return isSecure(uri) || isWebSocket(uri.getScheme());
    }
}
