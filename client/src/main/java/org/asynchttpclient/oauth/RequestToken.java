/*
 * Copyright 2010 Ning, Inc.
 *
 * This program is licensed to you under the Apache License, version 2.0
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

  RequestToken(String key, String token) {
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

  String getPercentEncodedKey() {
    return percentEncodedKey;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("{ key=");
    appendValue(sb, key);
    sb.append(", secret=");
    appendValue(sb, secret);
    sb.append("}");
    return sb.toString();
  }

  private void appendValue(StringBuilder sb, String value) {
    if (value == null) {
      sb.append("null");
    } else {
      sb.append('"');
      sb.append(value);
      sb.append('"');
    }
  }

  @Override
  public int hashCode() {
    return key.hashCode() + secret.hashCode();
  }

  @Override
  public boolean equals(Object o) {
    if (o == this)
      return true;
    if (o == null || o.getClass() != getClass())
      return false;
    RequestToken other = (RequestToken) o;
    return key.equals(other.key) && secret.equals(other.secret);
  }
}
