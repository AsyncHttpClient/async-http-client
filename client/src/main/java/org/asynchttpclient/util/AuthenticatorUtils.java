/*
 * Copyright (c) 2010-2013 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.ntlm.NtlmEngine;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.Body;
import org.asynchttpclient.request.body.generator.BodyGenerator;
import org.asynchttpclient.request.body.generator.ByteArrayBodyGenerator;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.spnego.SpnegoEngine;
import org.asynchttpclient.spnego.SpnegoEngineException;
import org.asynchttpclient.uri.Uri;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.asynchttpclient.Dsl.realm;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;

public final class AuthenticatorUtils {

    public static final String NEGOTIATE = "Negotiate";
    private static final int MAX_AUTH_INT_BODY_SIZE = 10 * 1024 * 1024;

    private AuthenticatorUtils() {
        // Prevent outside initialization
    }

    public static @Nullable String getHeaderWithPrefix(@Nullable List<String> authenticateHeaders, String prefix) {
        if (authenticateHeaders != null) {
            for (String authenticateHeader : authenticateHeaders) {
                if (authenticateHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    return authenticateHeader;
                }
            }
        }

        return null;
    }

    private static @Nullable String computeBasicAuthentication(@Nullable Realm realm) {
        return realm != null ? computeBasicAuthentication(realm.getPrincipal(), realm.getPassword(), realm.getCharset()) : null;
    }

    private static String computeBasicAuthentication(@Nullable String principal, @Nullable String password, Charset charset) {
        String s = principal + ':' + password;
        return "Basic " + Base64.getEncoder().encodeToString(s.getBytes(charset));
    }

    public static String computeRealmURI(Uri uri, boolean useAbsoluteURI, boolean omitQuery) {
        if (useAbsoluteURI) {
            return omitQuery && isNonEmpty(uri.getQuery()) ? uri.withNewQuery(null).toUrl() : uri.toUrl();
        } else {
            String path = uri.getNonEmptyPath();
            return omitQuery || !isNonEmpty(uri.getQuery()) ? path : path + '?' + uri.getQuery();
        }
    }

    private static String computeDigestAuthentication(Realm realm, Uri uri) {
        String realmUri = computeRealmURI(uri, realm.isUseAbsoluteURI(), realm.isOmitQuery());
        StringBuilder builder = new StringBuilder().append("Digest ");
        append(builder, "username", realm.getPrincipal(), true);
        append(builder, "realm", realm.getRealmName(), true);
        append(builder, "nonce", realm.getNonce(), true);
        append(builder, "uri", realmUri, true);
        if (isNonEmpty(realm.getAlgorithm())) {
            append(builder, "algorithm", realm.getAlgorithm(), false);
        }
        append(builder, "response", realm.getResponse(), true);
        if (realm.getOpaque() != null) {
            append(builder, "opaque", realm.getOpaque(), true);
        }
        if (realm.getScheme() == Realm.AuthScheme.DIGEST && realm.getCharset() == StandardCharsets.UTF_8) {
            append(builder, "charset", "UTF-8", false);
        }
        if (realm.getQop() != null) {
            append(builder, "qop", realm.getQop(), false);
            append(builder, "nc", realm.getNc(), false);
            append(builder, "cnonce", realm.getCnonce(), true);
        }
        // RFC7616: userhash parameter (optional, not implemented yet)
        builder.setLength(builder.length() - 2); // remove tailing ", "
        Charset wireCs = (realm.getCharset() == StandardCharsets.UTF_8)
                ? StandardCharsets.UTF_8
                : ISO_8859_1;
        return new String(StringUtils.charSequence2Bytes(builder, wireCs), wireCs);
    }

    /**
     * Calculates the digest response value for HTTP Digest Authentication.
     * This method computes HA1 and HA2 (including entity-body hash for auth-int).
     *
     * @param realm   The authentication realm containing credentials and challenge info
     * @param request The HTTP request (needed for method, uri, and body)
     * @return The computed response hex string
     * @throws UnsupportedOperationException if qop=auth-int but body cannot be hashed
     */
    static String computeDigestResponse(Realm realm, Request request) {
        String algorithm = realm.getAlgorithm() != null ? realm.getAlgorithm() : "MD5";
        String qop = realm.getQop() != null ? realm.getQop() : "auth";

        String hashAlgorithm = algorithm.replace("-sess", "");
        Charset wireCharset = realm.getCharset() != null ?
                realm.getCharset() : StandardCharsets.ISO_8859_1;

        // Calculate HA1
        String ha1 = calculateHA1(realm, algorithm);

        // Get request URI
        Uri uri = request.getUri();
        String requestUri = uri.getPath() +
                (uri.getQuery() != null ? "?" + uri.getQuery() : "");

        // Calculate HA2
        String ha2;
        if ("auth-int".equals(qop)) {
            String bodyHash = computeBodyHash(request, realm);
            ha2 = calculateHA2AuthInt(request, requestUri, bodyHash, hashAlgorithm, wireCharset);
        } else {
            // Regular auth: HA2 = H(method:uri)
            String a2Plain = request.getMethod() + ":" + requestUri;
            MessageDigest md = MessageDigestUtils.pooledMessageDigest(hashAlgorithm);
            try {
                md.update(a2Plain.getBytes(wireCharset));
                ha2 = MessageDigestUtils.bytesToHex(md.digest());
            } finally {
                md.reset();
            }
        }

        // Build final response
        String nc = realm.getNc() != null ? realm.getNc() : "00000001";
        String cnonce = realm.getCnonce();
        String nonce = realm.getNonce();

        // response = H(HA1:nonce:nc:cnonce:qop:HA2)
        String responseInput = ha1 + ":" + nonce + ":" + nc + ":" +
                cnonce + ":" + qop + ":" + ha2;

        MessageDigest md = MessageDigestUtils.pooledMessageDigest(hashAlgorithm);
        try {
            md.update(responseInput.getBytes(StandardCharsets.ISO_8859_1));
            return MessageDigestUtils.bytesToHex(md.digest());
        } finally {
            md.reset();
        }
    }

    /**
     * Calculates the HA1 value for HTTP Digest Authentication.
     * This method handles both regular and session-based HA1 calculations.
     *
     * @param realm     The authentication realm containing credentials and challenge info
     * @param algorithm The digest algorithm (e.g., "MD5", "MD5-sess")
     * @return The computed HA1 hex string
     */
    private static String calculateHA1(Realm realm, String algorithm) {
        Charset wireCs = realm.getCharset() != null ? realm.getCharset() : StandardCharsets.ISO_8859_1;
        String a1Base = realm.getPrincipal() + ':' + realm.getRealmName() + ':' + realm.getPassword();
        String hashAlgorithm = algorithm.replace("-sess", "");

        MessageDigest md = MessageDigestUtils.pooledMessageDigest(hashAlgorithm);
        try {
            md.update(a1Base.getBytes(wireCs));
            String ha1 = MessageDigestUtils.bytesToHex(md.digest());


            if (algorithm.endsWith("-sess")) {
                // For -sess: HA1 = H(H(username:realm:password):nonce:cnonce)
                String sessInput = ha1 + ":" + realm.getNonce() + ":" + realm.getCnonce();
                md.reset();
                md.update(sessInput.getBytes(StandardCharsets.ISO_8859_1));
                ha1 = MessageDigestUtils.bytesToHex(md.digest());
            }

            return ha1;
        } finally {
            md.reset();
        }
    }

    /**
     * Calculates the HA2 value for HTTP Digest Authentication.
     * This method handles both auth and auth-int cases.
     *
     * @param request       The HTTP request (needed for method, uri, and body)
     * @param requestUri    The request URI
     * @param bodyHash      The entity-body hash (for auth-int, can be empty for auth)
     * @param hashAlgorithm The digest algorithm (e.g., "MD5")
     * @param wireCs        The charset used for wire encoding
     * @return The computed HA2 hex string
     */
    private static String calculateHA2AuthInt(Request request, String requestUri, String bodyHash, String hashAlgorithm, Charset wireCs) {
        String a2Plain = request.getMethod() + ':' + requestUri + ':' + bodyHash;
        MessageDigest md = MessageDigestUtils.pooledMessageDigest(hashAlgorithm);
        try {
            md.update(a2Plain.getBytes(wireCs));
            return MessageDigestUtils.bytesToHex(md.digest());
        } finally {
            md.reset();   // return clean to pool
        }
    }

    static String computeBodyHash(Request request, Realm realm) {

        if (request.getStringData() == null &&
                request.getByteData() == null &&
                request.getByteBufData() == null &&
                request.getByteBufferData() == null &&
                request.getBodyGenerator() == null) {

            // No body to hash, return hash of empty string

            String algorithm = realm.getAlgorithm() != null ? realm.getAlgorithm() : "MD5";
            String hashAlgorithm = algorithm.replace("-sess", "");

            MessageDigest md = MessageDigestUtils.pooledMessageDigest(hashAlgorithm);
            try {
                return MessageDigestUtils.bytesToHex(md.digest());
            } finally {
                md.reset();
            }
        }

        String algorithm = realm.getAlgorithm() != null ? realm.getAlgorithm() : "MD5";
        String hashAlgorithm = algorithm.replace("-sess", "");
        Charset charset = resolveCharset(request, realm);


        if (request.getStringData() != null) {
            MessageDigest md = MessageDigestUtils.pooledMessageDigest(hashAlgorithm);
            try {
                md.update(request.getStringData().getBytes(charset));
                return MessageDigestUtils.bytesToHex(md.digest());
            } finally {
                md.reset();
            }
        }

        if (request.getByteBufData() != null) {
            MessageDigest md = MessageDigestUtils.pooledMessageDigest(hashAlgorithm);
            try {
                ByteBuf buf = request.getByteBufData();
                int idx = buf.readerIndex();
                int len = buf.readableBytes();

                byte[] tmp = new byte[len];
                buf.getBytes(idx, tmp);   // copy once
                md.update(tmp);

                return MessageDigestUtils.bytesToHex(md.digest());
            } finally {
                md.reset();
            }
        }


        if (request.getByteBufferData() != null) {
            MessageDigest md = MessageDigestUtils.pooledMessageDigest(hashAlgorithm);
            try {
                ByteBuffer bb = request.getByteBufferData().asReadOnlyBuffer();
                bb.position(0);
                md.update(bb);
                return MessageDigestUtils.bytesToHex(md.digest());
            } finally {
                md.reset();
            }
        }

        if (request.getByteData() != null) {
            MessageDigest md = MessageDigestUtils.pooledMessageDigest(hashAlgorithm);
            try {
                md.update(request.getByteData());
                return MessageDigestUtils.bytesToHex(md.digest());
            } finally {
                md.reset();
            }
        }

        // Handle BodyGenerator
        if (request.getBodyGenerator() != null) {
            return bufferAndHashBodyGenerator(request.getBodyGenerator(), hashAlgorithm);
        }

        throw new IllegalStateException("Unexpected request body state");

    }

    /**
     * Resolve the charset used to read / hash a request body.
     * Order of precedence:
     * 1) request.getCharset()      – per-request override
     * 2) realm.getCharset()        – negotiated via RFC 7616 (e.g. UTF-8)
     * 3) ISO-8859-1                – RFC default
     */
    private static Charset resolveCharset(Request request, Realm realm) {
        Charset cs = request.getCharset();
        if (cs != null) {
            return cs;
        }
        cs = realm.getCharset();
        return (cs != null) ? cs : StandardCharsets.ISO_8859_1;
    }

    /**
     * Buffers the body from the given BodyGenerator and computes its hash.
     * This is used for auth-int where the body needs to be hashed.
     *
     * @param gen           The BodyGenerator to read from
     * @param hashAlgorithm The hash algorithm to use (e.g., "MD5", "SHA-256")
     * @return The hex string of the computed hash
     * @throws UnsupportedOperationException if the body is too large or unsupported type
     */
    private static String bufferAndHashBodyGenerator(BodyGenerator gen, String hashAlgorithm) {
        MessageDigest md = MessageDigestUtils.pooledMessageDigest(hashAlgorithm);
        // Size guard
        if (gen instanceof ByteArrayBodyGenerator) {
            ByteArrayBodyGenerator bag = (ByteArrayBodyGenerator) gen;

            long size = bag.createBody().getContentLength();
            if (size > MAX_AUTH_INT_BODY_SIZE) {
                throw new UnsupportedOperationException("auth-int not supported for ByteArrayBodyGenerator >10 MB");
            }
        } else if (gen instanceof FileBodyGenerator) {
            FileBodyGenerator fg = (FileBodyGenerator) gen;

            long fileSize = fg.getFile().length();
            if (fileSize > MAX_AUTH_INT_BODY_SIZE) {
                throw new UnsupportedOperationException("auth-int not supported for files > 10 MB");
            }
            try {
                byte[] bytes = Files.readAllBytes(fg.getFile().toPath());  // may throw IOException
                md.update(bytes);
                return MessageDigestUtils.bytesToHex(md.digest());
            } catch (IOException ioe) {
                throw new RuntimeException("Failed to read file for auth-int hash", ioe);
            }
        } else {
            throw new UnsupportedOperationException("auth-int currently supports only ByteArrayBodyGenerator and FileBodyGenerator");
        }

        ByteBuf tmp = Unpooled.buffer(8192);

        try (Body body = gen.createBody()) {
            Body.BodyState state;
            while ((state = body.transferTo(tmp)) != Body.BodyState.STOP) {
                if (state == Body.BodyState.SUSPEND) {
                    continue;               // nothing new yet
                }
                int len = tmp.writerIndex();
                byte[] buf = new byte[len];
                tmp.getBytes(0, buf);
                md.update(buf);
                tmp.clear();
            }
            return MessageDigestUtils.bytesToHex(md.digest());

        } catch (IOException ioe) {
            throw new RuntimeException("Failed to hash request body", ioe);
        } finally {
            try {
                md.reset();
            } finally {
                tmp.release();
            }
        }
    }


    private static void append(StringBuilder builder, String name, @Nullable String value, boolean quoted) {
        builder.append(name).append('=');
        if (quoted) {
            builder.append('"').append(value).append('"');
        } else {
            builder.append(value);
        }
        builder.append(", ");
    }

    public static @Nullable String perConnectionProxyAuthorizationHeader(Request request, @Nullable Realm proxyRealm) {
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

    public static @Nullable String perRequestProxyAuthorizationHeader(Request request, @Nullable Realm proxyRealm) {
        String proxyAuthorization = null;
        if (proxyRealm != null && proxyRealm.isUsePreemptiveAuth()) {

            switch (proxyRealm.getScheme()) {
                case BASIC:
                    proxyAuthorization = computeBasicAuthentication(proxyRealm);
                    break;
                case DIGEST:
                    if (isNonEmpty(proxyRealm.getNonce())) {
                        // update realm with request information
                        final Uri uri = request.getUri();
                        Realm.Builder realmBuilder = realm(proxyRealm)
                                .setUri(uri)
                                .setMethodName(request.getMethod());

                        if ("auth-int".equals(proxyRealm.getQop())) {
                            String response = computeDigestResponse(proxyRealm, request);
                            realmBuilder.setResponse(response);
                        }

                        proxyRealm = realmBuilder.build();
                        proxyAuthorization = computeDigestAuthentication(proxyRealm, uri);
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

    public static @Nullable String perConnectionAuthorizationHeader(Request request, @Nullable ProxyServer proxyServer,
                                                                    @Nullable Realm realm) {
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
                    if (proxyServer != null) {
                        host = proxyServer.getHost();
                    } else if (request.getVirtualHost() != null) {
                        host = request.getVirtualHost();
                    } else {
                        host = request.getUri().getHost();
                    }

                    try {
                        authorizationHeader = NEGOTIATE + ' ' + SpnegoEngine.instance(
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

    public static @Nullable String perRequestAuthorizationHeader(Request request, @Nullable Realm realm) {
        String authorizationHeader = null;
        if (realm != null && realm.isUsePreemptiveAuth()) {

            switch (realm.getScheme()) {
                case BASIC:
                    authorizationHeader = computeBasicAuthentication(realm);
                    break;
                case DIGEST:
                    if (isNonEmpty(realm.getNonce())) {
                        // update realm with request information
                        final Uri uri = request.getUri();
                        Realm.Builder realmBuilder = realm(realm)
                                .setUri(uri)
                                .setMethodName(request.getMethod());
                        if ("auth-int".equals(realm.getQop())) {
                            String response = computeDigestResponse(realmBuilder.build(), request);
                            realmBuilder.setResponse(response);
                        }

                        realm = realmBuilder.build();
                        authorizationHeader = computeDigestAuthentication(realm, uri);
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
