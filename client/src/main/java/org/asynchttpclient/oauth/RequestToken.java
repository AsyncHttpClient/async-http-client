/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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
        this.secret = token;
        this.percentEncodedKey = Utf8UrlEncoder.percentEncodeQueryElement(key);
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
