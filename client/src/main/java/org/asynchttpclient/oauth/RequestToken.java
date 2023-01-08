/*
 *    Copyright (c) 2017-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.oauth;

import org.asynchttpclient.util.Utf8UrlEncoder;

/**
 * Value class used for OAuth tokens (request secret, access secret);
 * simple container with two parts, public id part ("key") and
 * confidential ("secret") part.
 */
public class RequestToken {
    private final String key;
    private final String secret;
    private final String percentEncodedKey;

    public RequestToken(String key, String token) {
        this.key = key;
        secret = token;
        percentEncodedKey = Utf8UrlEncoder.percentEncodeQueryElement(key);
    }

    public String getKey() {
        return key;
    }

    public String getSecret() {
        return secret;
    }

    public String getPercentEncodedKey() {
        return percentEncodedKey;
    }
}
