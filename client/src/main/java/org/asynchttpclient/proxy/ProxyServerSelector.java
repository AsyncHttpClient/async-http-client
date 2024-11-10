/*
 *    Copyright (c) 2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.proxy;

import org.asynchttpclient.uri.Uri;
import org.jspecify.annotations.Nullable;

/**
 * Selector for a proxy server
 */
@FunctionalInterface
public interface ProxyServerSelector {

    /**
     * A selector that always selects no proxy.
     */
    ProxyServerSelector NO_PROXY_SELECTOR = uri -> null;

    /**
     * Select a proxy server to use for the given URI.
     *
     * @param uri The URI to select a proxy server for.
     * @return The proxy server to use, if any.  May return null.
     */
    @Nullable
    ProxyServer select(Uri uri);
}
