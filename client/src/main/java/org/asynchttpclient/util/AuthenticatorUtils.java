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

/**
 * Utility class for handling HTTP authentication mechanisms.
 * <p>
 * This class provides methods to compute and generate authentication headers for various
 * authentication schemes including Basic, Digest, NTLM, Kerberos, and SPNEGO. It supports
 * both direct request authentication and proxy authentication.
 * </p>
 */
public final class AuthenticatorUtils {

  /**
   * The "Negotiate" authentication scheme identifier used for Kerberos and SPNEGO.
   */
  public static final String NEGOTIATE = "Negotiate";

  /**
   * Searches for an authentication header that starts with the specified prefix.
   * <p>
   * The comparison is case-insensitive.
   * </p>
   *
   * @param authenticateHeaders the list of authentication headers to search
   * @param prefix              the prefix to look for
   * @return the first header starting with the prefix, or null if not found
   */
  public static String getHeaderWithPrefix(List<String> authenticateHeaders, String prefix) {
    if (authenticateHeaders != null) {
      for (String authenticateHeader : authenticateHeaders) {
        if (authenticateHeader.regionMatches(true, 0, prefix, 0, prefix.length()))
          return authenticateHeader;
      }
    }

    return null;
  }

  /**
   * Computes the Basic authentication header value from a realm.
   *
   * @param realm the realm containing authentication credentials
   * @return the computed Basic authentication header value, or null if realm is null
   */
  private static String computeBasicAuthentication(Realm realm) {
    return realm != null ? computeBasicAuthentication(realm.getPrincipal(), realm.getPassword(), realm.getCharset()) : null;
  }

  /**
   * Computes the Basic authentication header value from credentials.
   * <p>
   * Encodes the principal and password in the format "username:password" using Base64 encoding.
   * </p>
   *
   * @param principal the username
   * @param password  the password
   * @param charset   the character set to use for encoding
   * @return the computed Basic authentication header value in the format "Basic [base64]"
   */
  private static String computeBasicAuthentication(String principal, String password, Charset charset) {
    String s = principal + ":" + password;
    return "Basic " + Base64.getEncoder().encodeToString(s.getBytes(charset));
  }

  /**
   * Computes the URI to use for the realm based on authentication requirements.
   * <p>
   * This method constructs the appropriate URI format based on whether an absolute URI
   * is required and whether the query string should be included.
   * </p>
   *
   * @param uri            the request URI
   * @param useAbsoluteURI whether to use the absolute URI format
   * @param omitQuery      whether to omit the query string
   * @return the computed realm URI string
   */
  public static String computeRealmURI(Uri uri, boolean useAbsoluteURI, boolean omitQuery) {
    if (useAbsoluteURI) {
      return omitQuery && MiscUtils.isNonEmpty(uri.getQuery()) ? uri.withNewQuery(null).toUrl() : uri.toUrl();
    } else {
      String path = uri.getNonEmptyPath();
      return omitQuery || !MiscUtils.isNonEmpty(uri.getQuery()) ? path : path + "?" + uri.getQuery();
    }
  }

  /**
   * Computes the Digest authentication header value from a realm.
   * <p>
   * Constructs the Digest authentication header according to RFC 2617, including all
   * required and optional directives such as username, realm, nonce, uri, response,
   * algorithm, opaque, qop, nc, and cnonce.
   * </p>
   *
   * @param realm the realm containing authentication parameters
   * @return the computed Digest authentication header value
   */
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

  /**
   * Appends a name-value pair to the StringBuilder for Digest authentication.
   *
   * @param builder the StringBuilder to append to
   * @param name    the directive name
   * @param value   the directive value
   * @param quoted  whether the value should be quoted
   */
  private static void append(StringBuilder builder, String name, String value, boolean quoted) {
    builder.append(name).append('=');
    if (quoted)
      builder.append('"').append(value).append('"');
    else
      builder.append(value);

    builder.append(", ");
  }

  /**
   * Computes the proxy authorization header for connection-level authentication schemes.
   * <p>
   * This method handles authentication schemes that require connection-level negotiation,
   * such as NTLM, Kerberos, and SPNEGO. It generates the Type 1 message for NTLM authentication
   * if not already present in the request headers.
   * </p>
   *
   * @param request    the HTTP request
   * @param proxyRealm the proxy authentication realm
   * @return the proxy authorization header value, or null if not applicable
   */
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

  /**
   * Computes the proxy authorization header for request-level authentication schemes.
   * <p>
   * This method handles authentication schemes that can be sent with each request,
   * such as Basic and Digest authentication. For connection-level schemes (NTLM, Kerberos, SPNEGO),
   * this method returns null as they are handled by {@link #perConnectionProxyAuthorizationHeader}.
   * </p>
   *
   * @param request    the HTTP request
   * @param proxyRealm the proxy authentication realm
   * @return the proxy authorization header value, or null if not applicable
   * @throws IllegalStateException if an invalid authentication scheme is specified
   */
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

  /**
   * Computes the authorization header for connection-level authentication schemes.
   * <p>
   * This method handles authentication schemes that require connection-level negotiation,
   * including NTLM, Kerberos, and SPNEGO. For NTLM, it generates the Type 1 message.
   * For Kerberos and SPNEGO, it generates the appropriate token using the SPNEGO engine.
   * </p>
   *
   * @param request     the HTTP request
   * @param proxyServer the proxy server, if any
   * @param realm       the authentication realm
   * @return the authorization header value, or null if not applicable
   * @throws RuntimeException if SPNEGO token generation fails
   */
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

  /**
   * Computes the authorization header for request-level authentication schemes.
   * <p>
   * This method handles authentication schemes that can be sent with each request,
   * such as Basic and Digest authentication. For connection-level schemes (NTLM, Kerberos, SPNEGO),
   * this method returns null as they are handled by {@link #perConnectionAuthorizationHeader}.
   * </p>
   *
   * @param request the HTTP request
   * @param realm   the authentication realm
   * @return the authorization header value, or null if not applicable
   * @throws IllegalStateException if an invalid authentication scheme is specified
   */
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
