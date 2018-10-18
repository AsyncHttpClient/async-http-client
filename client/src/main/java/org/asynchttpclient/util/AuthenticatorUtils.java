/*
 * Copyright (c) 2010-2013 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.util;

import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.ntlm.NtlmEngine;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.spnego.SpnegoEngineException;
import org.asynchttpclient.uri.Uri;

import java.nio.charset.Charset;
import java.util.Base64;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.asynchttpclient.Dsl.realm;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

public final class AuthenticatorUtils {

  public static final String NEGOTIATE = "Negotiate";

  public static String getHeaderWithPrefix(List<String> authenticateHeaders, String prefix) {
    if (authenticateHeaders != null) {
      for (String authenticateHeader : authenticateHeaders) {
        if (authenticateHeader.regionMatches(true, 0, prefix, 0, prefix.length()))
          return authenticateHeader;
      }
    }

    return null;
  }

  private static String computeBasicAuthentication(Realm realm) {
    return realm != null ? computeBasicAuthentication(realm.getPrincipal(), realm.getPassword(), realm.getCharset()) : null;
  }

  private static String computeBasicAuthentication(String principal, String password, Charset charset) {
    String s = principal + ":" + password;
    return "Basic " + Base64.getEncoder().encodeToString(s.getBytes(charset));
  }

  public static String computeRealmURI(Uri uri, boolean useAbsoluteURI, boolean omitQuery) {
    if (useAbsoluteURI) {
      return omitQuery && MiscUtils.isNonEmpty(uri.getQuery()) ? uri.withNewQuery(null).toUrl() : uri.toUrl();
    } else {
      String path = uri.getNonEmptyPath();
      return omitQuery || !MiscUtils.isNonEmpty(uri.getQuery()) ? path : path + "?" + uri.getQuery();
    }
  }

  private static String computeDigestAuthentication(Realm realm) {

    String realmUri = computeRealmURI(realm.getUri(), realm.isUseAbsoluteURI(), realm.isOmitQuery());

    StringBuilder builder = new StringBuilder().append("Digest ");
    append(builder, "username", realm.getPrincipal(), true);
    append(builder, "realm", realm.getRealmName(), true);
    append(builder, "nonce", realm.getNonce(), true);
    append(builder, "uri", realmUri, true);
    if (isNonEmpty(realm.getAlgorithm()))
      append(builder, "algorithm", realm.getAlgorithm(), false);

    append(builder, "response", realm.getResponse(), true);

    if (realm.getOpaque() != null)
      append(builder, "opaque", realm.getOpaque(), true);

    if (realm.getQop() != null) {
      append(builder, "qop", realm.getQop(), false);
      // nc and cnonce only sent if server sent qop
      append(builder, "nc", realm.getNc(), false);
      append(builder, "cnonce", realm.getCnonce(), true);
    }
    builder.setLength(builder.length() - 2); // remove tailing ", "

    // FIXME isn't there a more efficient way?
    return new String(StringUtils.charSequence2Bytes(builder, ISO_8859_1));
  }

  private static void append(StringBuilder builder, String name, String value, boolean quoted) {
    builder.append(name).append('=');
    if (quoted)
      builder.append('"').append(value).append('"');
    else
      builder.append(value);

    builder.append(", ");
  }

  public static String perConnectionProxyAuthorizationHeader(Request request, Realm proxyRealm) {
    String proxyAuthorization = null;
    if (proxyRealm != null && proxyRealm.isUsePreemptiveAuth()) {
      switch (proxyRealm.getScheme()) {
        case NTLM:
        case KERBEROS:
        case SPNEGO:
          List<String> auth = request.getHeaders().getAll(PROXY_AUTHORIZATION);
          if (getHeaderWithPrefix(auth, "NTLM") == null) {
            String msg = NtlmEngine.INSTANCE.generateType1Msg();
            proxyAuthorization = "NTLM " + msg;
          }

          break;
        default:
      }
    }

    return proxyAuthorization;
  }

  public static String perRequestProxyAuthorizationHeader(Request request, Realm proxyRealm) {

    String proxyAuthorization = null;
    if (proxyRealm != null && proxyRealm.isUsePreemptiveAuth()) {

      switch (proxyRealm.getScheme()) {
        case BASIC:
          proxyAuthorization = computeBasicAuthentication(proxyRealm);
          break;
        case DIGEST:
          if (isNonEmpty(proxyRealm.getNonce())) {
            // update realm with request information
            proxyRealm = realm(proxyRealm)
                    .setUri(request.getUri())
                    .setMethodName(request.getMethod())
                    .build();
            proxyAuthorization = computeDigestAuthentication(proxyRealm);
          }
          break;
        case NTLM:
        case KERBEROS:
        case SPNEGO:
          // NTLM, KERBEROS and SPNEGO are only set on the first request with a connection,
          // see perConnectionProxyAuthorizationHeader
          break;
        default:
          throw new IllegalStateException("Invalid Authentication scheme " + proxyRealm.getScheme());
      }
    }

    return proxyAuthorization;
  }

  public static String perConnectionAuthorizationHeader(Request request, ProxyServer proxyServer, Realm realm) {
    String authorizationHeader = null;

    if (realm != null && realm.isUsePreemptiveAuth()) {
      switch (realm.getScheme()) {
        case NTLM:
          String msg = NtlmEngine.INSTANCE.generateType1Msg();
          authorizationHeader = "NTLM " + msg;
          break;
        case KERBEROS:
        case SPNEGO:
          String host;
          if (proxyServer != null)
            host = proxyServer.getHost();
          else if (request.getVirtualHost() != null)
            host = request.getVirtualHost();
          else
            host = request.getUri().getHost();

          try {
            authorizationHeader = NEGOTIATE + " " + SpnegoEngine.instance(
                realm.getPrincipal(),
                realm.getPassword(),
                realm.getServicePrincipalName(),
                realm.getRealmName(),
                realm.isUseCanonicalHostname(),
                realm.getCustomLoginConfig(),
                realm.getLoginContextName()).generateToken(host);
          } catch (SpnegoEngineException e) {
            throw new RuntimeException(e);
          }
          break;
        default:
          break;
      }
    }

    return authorizationHeader;
  }

  public static String perRequestAuthorizationHeader(Request request, Realm realm) {

    String authorizationHeader = null;

    if (realm != null && realm.isUsePreemptiveAuth()) {

      switch (realm.getScheme()) {
        case BASIC:
          authorizationHeader = computeBasicAuthentication(realm);
          break;
        case DIGEST:
          if (isNonEmpty(realm.getNonce())) {
            // update realm with request information
            realm = realm(realm)
                    .setUri(request.getUri())
                    .setMethodName(request.getMethod())
                    .build();
            authorizationHeader = computeDigestAuthentication(realm);
          }
          break;
        case NTLM:
        case KERBEROS:
        case SPNEGO:
          // NTLM, KERBEROS and SPNEGO are only set on the first request with a connection,
          // see perConnectionAuthorizationHeader
          break;
        default:
          throw new IllegalStateException("Invalid Authentication " + realm);
      }
    }

    return authorizationHeader;
  }
}
