/*
 *    Copyright (c) 2018-2023 AsyncHttpClient Project. All rights reserved.
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

public enum ProxyType {
    HTTP(true), SOCKS_V4(false), SOCKS_V5(false);

    private final boolean http;

    ProxyType(boolean http) {
        this.http = http;
    }

    public boolean isHttp() {
        return http;
    }

    public boolean isSocks() {
        return !isHttp();
    }
}
