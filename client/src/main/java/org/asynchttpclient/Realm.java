/*
 * Copyright 2010-2013 Ning, Inc.
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
package org.asynchttpclient;

import org.asynchttpclient.uri.Uri;
import org.asynchttpclient.util.AuthenticatorUtils;
import org.asynchttpclient.util.StringBuilderPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.asynchttpclient.util.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.asynchttpclient.util.HttpConstants.Methods.GET;
import static org.asynchttpclient.util.MiscUtils.isNonEmpty;
import static org.asynchttpclient.util.StringUtils.appendBase16;
import static org.asynchttpclient.util.StringUtils.toHexString;
import org.asynchttpclient.util.MessageDigestUtils;

/**
 * This class is required when authentication is needed. The class support
 * BASIC, DIGEST, NTLM, SPNEGO and KERBEROS.
 */
public class Realm {

    private static final String DEFAULT_NC = "00000001";

    private final @Nullable String principal;
    private final @Nullable String password;
    private final AuthScheme scheme;
    private final @Nullable String realmName;
    private final @Nullable String nonce;
    private final @Nullable String algorithm;
    private final @Nullable String response;
    private final @Nullable String opaque;
    private final @Nullable String qop;
    private final String nc;
    private final @Nullable String cnonce;
    private final @Nullable Uri uri;
    private final boolean usePreemptiveAuth;
    private final Charset charset;
    private final String ntlmHost;
    private final String ntlmDomain;
    private final boolean useAbsoluteURI;
    private final boolean omitQuery;
    private final @Nullable Map<String, String> customLoginConfig;
    private final @Nullable String servicePrincipalName;
    private final boolean useCanonicalHostname;
    private final @Nullable String loginContextName;
    private final boolean stale;
    private final boolean userhash;
    private final @Nullable String sid;
    private final int maxIterationCount;
    private @Nullable String basicAuthHeader;

    private Realm(@Nullable AuthScheme scheme,
                  @Nullable String principal,
                  @Nullable String password,
                  @Nullable String realmName,
                  @Nullable String nonce,
                  @Nullable String algorithm,
                  @Nullable String response,
                  @Nullable String opaque,
                  @Nullable String qop,
                  String nc,
                  @Nullable String cnonce,
                  @Nullable Uri uri,
                  boolean usePreemptiveAuth,
                  Charset charset,
                  String ntlmDomain,
                  String ntlmHost,
                  boolean useAbsoluteURI,
                  boolean omitQuery,
                  @Nullable String servicePrincipalName,
                  boolean useCanonicalHostname,
                  @Nullable Map<String, String> customLoginConfig,
                  @Nullable String loginContextName,
                  boolean stale,
                  boolean userhash,
                  @Nullable String sid,
                  int maxIterationCount) {

        this.scheme = requireNonNull(scheme, "scheme");
        this.principal = principal;
        this.password = password;
        this.realmName = realmName;
        this.nonce = nonce;
        this.algorithm = algorithm;
        this.response = response;
        this.opaque = opaque;
        this.qop = qop;
        this.nc = nc;
        this.cnonce = cnonce;
        this.uri = uri;
        this.usePreemptiveAuth = usePreemptiveAuth;
        this.charset = charset;
        this.ntlmDomain = ntlmDomain;
        this.ntlmHost = ntlmHost;
        this.useAbsoluteURI = useAbsoluteURI;
        this.omitQuery = omitQuery;
        this.servicePrincipalName = servicePrincipalName;
        this.useCanonicalHostname = useCanonicalHostname;
        this.customLoginConfig = customLoginConfig;
        this.loginContextName = loginContextName;
        this.stale = stale;
        this.userhash = userhash;
        this.sid = sid;
        this.maxIterationCount = maxIterationCount;
    }

    public @Nullable String getPrincipal() {
        return principal;
    }

    public @Nullable String getPassword() {
        return password;
    }

    public AuthScheme getScheme() {
        return scheme;
    }

    public @Nullable String getRealmName() {
        return realmName;
    }

    public @Nullable String getNonce() {
        return nonce;
    }

    public @Nullable String getAlgorithm() {
        return algorithm;
    }

    public @Nullable String getResponse() {
        return response;
    }

    public @Nullable String getOpaque() {
        return opaque;
    }

    public @Nullable String getQop() {
        return qop;
    }

    public String getNc() {
        return nc;
    }

    public @Nullable String getCnonce() {
        return cnonce;
    }

    public @Nullable Uri getUri() {
        return uri;
    }

    public Charset getCharset() {
        return charset;
    }

    /**
     * Return true is preemptive authentication is enabled
     *
     * @return true is preemptive authentication is enabled
     */
    public boolean isUsePreemptiveAuth() {
        return usePreemptiveAuth;
    }

    /**
     * Return the NTLM domain to use. This value should map the JDK
     *
     * @return the NTLM domain
     */
    public String getNtlmDomain() {
        return ntlmDomain;
    }

    /**
     * Return the NTLM host.
     *
     * @return the NTLM host
     */
    public String getNtlmHost() {
        return ntlmHost;
    }

    public boolean isUseAbsoluteURI() {
        return useAbsoluteURI;
    }

    public boolean isOmitQuery() {
        return omitQuery;
    }

    public @Nullable Map<String, String> getCustomLoginConfig() {
        return customLoginConfig;
    }

    public @Nullable String getServicePrincipalName() {
        return servicePrincipalName;
    }

    public boolean isUseCanonicalHostname() {
        return useCanonicalHostname;
    }

    public @Nullable String getLoginContextName() {
        return loginContextName;
    }

    public boolean isStale() {
        return stale;
    }

    public boolean isUserhash() {
        return userhash;
    }

    public @Nullable String getSid() {
        return sid;
    }

    public int getMaxIterationCount() {
        return maxIterationCount;
    }

    /**
     * Returns the lazily computed and cached HTTP Basic authorization header for this immutable realm.
     * Always encodes {@link #getPrincipal()} and {@link #getPassword()} as a Basic header, regardless
     * of {@link #getScheme()}. Concurrent first calls may compute the same value more than once.
     *
     * @return the Basic authorization header
     * @since 3.0.12
     */
    public String getBasicAuthHeader() {
        String header = basicAuthHeader;
        if (header == null) {
            String s = principal + ':' + password;
            header = "Basic " + Base64.getEncoder().encodeToString(s.getBytes(charset));
            basicAuthHeader = header;
        }
        return header;
    }

    @Override
    public String toString() {
        return "Realm{" +
                "principal='" + principal + '\'' +
                ", password=" + (password == null ? "null" : "<redacted>") +
                ", scheme=" + scheme +
                ", realmName='" + realmName + '\'' +
                ", nonce='" + nonce + '\'' +
                ", algorithm='" + algorithm + '\'' +
                ", response=" + (response == null ? "null" : "<redacted>") +
                ", opaque='" + opaque + '\'' +
                ", qop='" + qop + '\'' +
                ", nc='" + nc + '\'' +
                ", cnonce='" + cnonce + '\'' +
                ", uri=" + (uri == null ? null : uri.toUrlWithoutUserInfo()) +
                ", usePreemptiveAuth=" + usePreemptiveAuth +
                ", charset=" + charset +
                ", ntlmHost='" + ntlmHost + '\'' +
                ", ntlmDomain='" + ntlmDomain + '\'' +
                ", useAbsoluteURI=" + useAbsoluteURI +
                ", omitQuery=" + omitQuery +
                ", customLoginConfig=" + customLoginConfig +
                ", servicePrincipalName='" + servicePrincipalName + '\'' +
                ", useCanonicalHostname=" + useCanonicalHostname +
                ", loginContextName='" + loginContextName + '\'' +
                '}';
    }

    public enum AuthScheme {
        BASIC, DIGEST, NTLM, SPNEGO, KERBEROS, SCRAM_SHA_256
    }

    /**
     * A builder for {@link Realm}
     */
    public static class Builder {

        private static final Logger LOGGER = LoggerFactory.getLogger(Builder.class);

        // cnonce must be unpredictable (RFC 7616 section 3.3), like the NTLM and SCRAM nonces
        private static final ThreadLocal<SecureRandom> CNONCE_RANDOM = ThreadLocal.withInitial(SecureRandom::new);

        private final @Nullable String principal;
        private final @Nullable String password;
        private @Nullable AuthScheme scheme;
        private @Nullable String realmName;
        private @Nullable String nonce;
        private @Nullable String algorithm;
        private @Nullable String response;
        private @Nullable String opaque;
        private @Nullable String qop;
        private String nc = DEFAULT_NC;
        private @Nullable String cnonce;
        private @Nullable Uri uri;
        private String methodName = GET;
        private boolean usePreemptive;
        private String ntlmDomain = System.getProperty("http.auth.ntlm.domain");
        private Charset charset = UTF_8;
        private String ntlmHost = "localhost";
        private boolean useAbsoluteURI;
        private boolean omitQuery;
        private Charset digestCharset = ISO_8859_1;   // RFC default
        /**
         * Kerberos/Spnego properties
         */
        private @Nullable Map<String, String> customLoginConfig;
        private @Nullable String servicePrincipalName;
        private boolean useCanonicalHostname;
        private @Nullable String loginContextName;
        private @Nullable String cs;
        private boolean stale;
        private boolean userhash;
        private @Nullable String entityBodyHash;
        private @Nullable String sid;
        private int maxIterationCount = 16_384;

        public Builder() {
            principal = null;
            password = null;
        }

        public Builder(@Nullable String principal, @Nullable String password) {
            this.principal = principal;
            this.password = password;
        }

        public Builder setNtlmDomain(String ntlmDomain) {
            this.ntlmDomain = ntlmDomain;
            return this;
        }

        public Builder setNtlmHost(String host) {
            ntlmHost = host;
            return this;
        }

        public Builder setScheme(AuthScheme scheme) {
            this.scheme = scheme;
            return this;
        }

        public Builder setRealmName(@Nullable String realmName) {
            this.realmName = realmName;
            return this;
        }

        public Builder setNonce(@Nullable String nonce) {
            this.nonce = nonce;
            return this;
        }

        public Builder setAlgorithm(@Nullable String algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public Builder setResponse(String response) {
            this.response = response;
            return this;
        }

        public Builder setOpaque(@Nullable String opaque) {
            this.opaque = opaque;
            return this;
        }

        public Builder setQop(@Nullable String qop) {
            if (isNonEmpty(qop)) {
                this.qop = qop;
            }
            return this;
        }

        public Builder setNc(String nc) {
            this.nc = nc;
            return this;
        }

        public Builder setUri(@Nullable Uri uri) {
            this.uri = uri;
            return this;
        }

        public Builder setMethodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder setUsePreemptiveAuth(boolean usePreemptiveAuth) {
            usePreemptive = usePreemptiveAuth;
            return this;
        }

        public Builder setUseAbsoluteURI(boolean useAbsoluteURI) {
            this.useAbsoluteURI = useAbsoluteURI;
            return this;
        }

        public Builder setOmitQuery(boolean omitQuery) {
            this.omitQuery = omitQuery;
            return this;
        }

        public Builder setCharset(Charset charset) {
            this.charset = charset;
            return this;
        }

        public Builder setCustomLoginConfig(@Nullable Map<String, String> customLoginConfig) {
            this.customLoginConfig = customLoginConfig;
            return this;
        }

        public Builder setServicePrincipalName(@Nullable String servicePrincipalName) {
            this.servicePrincipalName = servicePrincipalName;
            return this;
        }

        public Builder setUseCanonicalHostname(boolean useCanonicalHostname) {
            this.useCanonicalHostname = useCanonicalHostname;
            return this;
        }

        public Builder setLoginContextName(@Nullable String loginContextName) {
            this.loginContextName = loginContextName;
            return this;
        }

        public Builder setStale(boolean stale) {
            this.stale = stale;
            return this;
        }

        public boolean isStale() {
            return stale;
        }

        public Builder setUserhash(boolean userhash) {
            this.userhash = userhash;
            return this;
        }

        public Builder setEntityBodyHash(@Nullable String entityBodyHash) {
            this.entityBodyHash = entityBodyHash;
            return this;
        }

        public Builder setSid(@Nullable String sid) {
            this.sid = sid;
            return this;
        }

        public Builder setMaxIterationCount(int maxIterationCount) {
            this.maxIterationCount = maxIterationCount;
            return this;
        }

        public @Nullable String getQopValue() {
            return qop;
        }

        public @Nullable String getNonceValue() {
            return nonce;
        }

        private static @Nullable String parseRawQop(String rawQop) {
            String[] rawServerSupportedQops = rawQop.split(",");
            String[] serverSupportedQops = new String[rawServerSupportedQops.length];
            for (int i = 0; i < rawServerSupportedQops.length; i++) {
                serverSupportedQops[i] = rawServerSupportedQops[i].trim();
            }

            for (String rawServerSupportedQop : serverSupportedQops) {
                if ("auth".equals(rawServerSupportedQop)) {
                    return rawServerSupportedQop;
                }
            }

            // auth-int is deliberately not selected. Its rspauth signs the response entity-body, which has
            // not been read when the Authentication-Info header is processed, so the value the server signed
            // cannot be derived there and mutual authentication is skipped for the whole exchange. A peer
            // that chooses the challenge could therefore switch mutual authentication off simply by offering
            // auth-int on its own. Declining to negotiate a mode we cannot verify keeps that decision ours.
            //
            // Returning no qop is not free: the exchange falls back to the RFC 2069 digest, which carries no
            // cnonce and no nonce count and so has weaker replay protection than the auth-int it replaces.
            // That is still preferable to authenticating with no mutual check at all, but it is a downgrade
            // and the caller deserves to know it happened.
            for (String rawServerSupportedQop : serverSupportedQops) {
                if ("auth-int".equalsIgnoreCase(rawServerSupportedQop)) {
                    LOGGER.warn("Server offered qop=\"auth-int\" only. Its rspauth cannot be verified before the "
                            + "response body is read, so it is not negotiated and this exchange falls back to the "
                            + "RFC 2069 digest, which has weaker replay protection.");
                    break;
                }
            }
            return null;
        }

        public Builder parseWWWAuthenticateHeader(String headerLine) {
            setRealmName(matchParam(headerLine, "realm"))
                    .setNonce(matchParam(headerLine, "nonce"))
                    .setOpaque(matchParam(headerLine, "opaque"))
                    .setScheme(challengedScheme(headerLine, nonce));
            String algorithm = matchParam(headerLine, "algorithm");
            String cs = matchParam(headerLine, "charset");
            if ("UTF-8".equalsIgnoreCase(cs)) {
                this.digestCharset = UTF_8;
            }
            if (isNonEmpty(algorithm)) {
                setAlgorithm(algorithm);
            }

            String rawQop = matchParam(headerLine, "qop");
            if (rawQop != null) {
                setQop(parseRawQop(rawQop));
            }

            // Parse stale flag
            String staleStr = matchParam(headerLine, "stale");
            this.stale = "true".equalsIgnoreCase(staleStr);

            // Parse userhash flag
            String userhashStr = matchParam(headerLine, "userhash");
            this.userhash = "true".equalsIgnoreCase(userhashStr);

            return this;
        }

        public Builder parseProxyAuthenticateHeader(String headerLine) {
            setRealmName(matchParam(headerLine, "realm"))
                    .setNonce(matchParam(headerLine, "nonce"))
                    .setOpaque(matchParam(headerLine, "opaque"))
                    .setScheme(challengedScheme(headerLine, nonce));
            String algorithm = matchParam(headerLine, "algorithm");
            if (isNonEmpty(algorithm)) {
                setAlgorithm(algorithm);
            }

            String rawQop = matchParam(headerLine, "qop");
            if (rawQop != null) {
                setQop(parseRawQop(rawQop));
            }

            String cs = matchParam(headerLine, "charset");
            if ("UTF-8".equalsIgnoreCase(cs)) {
                this.digestCharset = UTF_8;
            }

            // Parse stale flag
            String staleStr = matchParam(headerLine, "stale");
            this.stale = "true".equalsIgnoreCase(staleStr);

            // Parse userhash flag
            String userhashStr = matchParam(headerLine, "userhash");
            this.userhash = "true".equalsIgnoreCase(userhashStr);

            return this;
        }

        /**
         * The scheme a parsed challenge authenticates with.
         * <p>
         * A challenge that announces itself as Digest stays Digest even when its parameters do not parse.
         * Deciding this from the presence of a nonce instead meant any challenge we failed to read became a
         * Basic one, and answering it put the password on the wire in the clear - the single thing Digest
         * exists to prevent. A hostile server cannot obtain that by asking for Basic directly, because a
         * Digest realm refuses a challenge that is not Digest, so a parser slip must not hand over what the
         * honest path withholds. A Digest challenge with no nonce produces no Authorization header at all,
         * so the request fails rather than leaking.
         */
        private static AuthScheme challengedScheme(@Nullable String headerLine, @Nullable String nonce) {
            if (isNonEmpty(nonce)) {
                return AuthScheme.DIGEST;
            }
            if (headerLine == null) {
                return AuthScheme.BASIC;
            }
            int start = 0;
            while (start < headerLine.length() && headerLine.charAt(start) == ' ') {
                start++;
            }
            return headerLine.regionMatches(true, start, "Digest", 0, 6) ? AuthScheme.DIGEST : AuthScheme.BASIC;
        }

        /**
         * Extracts the value of a token from a WWW-Authenticate or Proxy-Authenticate header line.
         * Handles both quoted values (token="value") and unquoted values (token=value).
         * Example: matchParam('Digest realm="test", algorithm=SHA-256', "realm") returns "test"
         * Example: matchParam('Digest algorithm=SHA-256', "algorithm") returns "SHA-256"
         */
        public static @Nullable String matchParam(String headerLine, String token) {
            if (headerLine == null || token == null) {
                return null;
            }
            int len = headerLine.length();
            int tokenLen = token.length();
            int i = 0;
            while (i < len) {
                int idx = headerLine.indexOf('=', i);
                if (idx == -1) {
                    return null;
                }
                // Walk backwards from '=' to find the start of the key (skip whitespace)
                int keyEnd = idx;
                while (keyEnd > i && headerLine.charAt(keyEnd - 1) == ' ') {
                    keyEnd--;
                }
                int keyStart = keyEnd - tokenLen;
                if (keyStart >= 0
                        && headerLine.regionMatches(true, keyStart, token, 0, tokenLen)
                        && (keyStart == 0 || headerLine.charAt(keyStart - 1) == ' ' || headerLine.charAt(keyStart - 1) == ',')) {
                    // Found matching token, now extract value
                    int valStart = idx + 1;
                    // skip whitespace after '='
                    while (valStart < len && headerLine.charAt(valStart) == ' ') {
                        valStart++;
                    }
                    if (valStart < len && headerLine.charAt(valStart) == '"') {
                        return unquote(headerLine, valStart);
                    } else {
                        // Unquoted value — terminated by ',' or end-of-string
                        int valEnd = valStart;
                        while (valEnd < len && headerLine.charAt(valEnd) != ',' && headerLine.charAt(valEnd) != ' ') {
                            valEnd++;
                        }
                        if (valEnd > valStart) {
                            return headerLine.substring(valStart, valEnd);
                        }
                        return null;
                    }
                }
                // Not our token. If its value is a quoted-string, step over the whole thing: its content is
                // opaque data, and a '=' inside it is not a parameter separator. Without this a value such as
                // opaque="o, qop=auth-int, z" would let the peer forge any auth-param it likes.
                int skipFrom = idx + 1;
                while (skipFrom < len && headerLine.charAt(skipFrom) == ' ') {
                    skipFrom++;
                }
                i = skipFrom < len && headerLine.charAt(skipFrom) == '"'
                        ? endOfQuotedString(headerLine, skipFrom)
                        : idx + 1;
            }
            return null;
        }

        /**
         * The value of the quoted-string starting at {@code open}, with RFC 7230 Section 3.2.6 quoted-pairs
         * decoded. The emitting side escapes {@code "} and {@code \} in these values, so reading them back
         * verbatim would yield a different string than the peer hashed - a realm as ordinary as
         * {@code DOMAIN\Users} would then fail every digest comparison.
         * <p>
         * A backslash before any other character is kept as written, which is what lets the unescaped
         * spelling of that same realm survive. The rule is adapted from the quoted-content handling in
         * Apache HttpComponents Core ({@code org.apache.hc.core5.util.Tokenizer}), Apache License 2.0,
         * reimplemented here against this class's index-based scanning rather than a cursor.
         */
        private static @Nullable String unquote(String headerLine, int open) {
            int len = headerLine.length();
            StringBuilder sb = new StringBuilder(len - open);
            for (int i = open + 1; i < len; i++) {
                char c = headerLine.charAt(i);
                if (c == '\\' && i + 1 < len) {
                    char escaped = headerLine.charAt(i + 1);
                    if (escaped == '"' || escaped == '\\') {
                        sb.append(escaped);
                    } else {
                        // Only those two are ever escaped on the way out, so a backslash before anything
                        // else was meant literally: an unescaped realm such as DOMAIN\Users is common
                        // enough that consuming the U would corrupt the value and fail every comparison.
                        sb.append(c).append(escaped);
                    }
                    i++;
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
            // Unterminated quoted-string
            return null;
        }

        /**
         * The index just past the quoted-string starting at {@code open}, honouring quoted-pairs so an
         * escaped quote does not look like the terminator. Returns the end of the line if unterminated.
         */
        private static int endOfQuotedString(String headerLine, int open) {
            int len = headerLine.length();
            for (int i = open + 1; i < len; i++) {
                char c = headerLine.charAt(i);
                if (c == '\\' && i + 1 < len) {
                    char escaped = headerLine.charAt(i + 1);
                    if (escaped == '"' || escaped == '\\') {
                        i++;
                    }
                } else if (c == '"') {
                    return i + 1;
                }
            }
            return len;
        }

        private void newCnonce() {
            byte[] b = new byte[8];
            CNONCE_RANDOM.get().nextBytes(b);
            cnonce = toHexString(b);
        }

        private static byte[] digestFromRecycledStringBuilder(StringBuilder sb, MessageDigest md, Charset enc) {
            md.update(StringUtils.charSequence2ByteBuffer(sb, enc));
            sb.setLength(0);
            return md.digest();
        }

        private static MessageDigest getDigestInstance(String algorithm) {
            if ("SHA-512/256".equalsIgnoreCase(algorithm)) algorithm = "SHA-512-256";
            if (algorithm == null || "MD5".equalsIgnoreCase(algorithm) || "MD5-sess".equalsIgnoreCase(algorithm)) {
                return MessageDigestUtils.pooledMd5MessageDigest();
            } else if ("SHA-256".equalsIgnoreCase(algorithm) || "SHA-256-sess".equalsIgnoreCase(algorithm)) {
                return MessageDigestUtils.pooledSha256MessageDigest();
            } else if ("SHA-512-256".equalsIgnoreCase(algorithm) || "SHA-512-256-sess".equalsIgnoreCase(algorithm)) {
                return MessageDigestUtils.pooledSha512_256MessageDigest();
            } else {
                throw new UnsupportedOperationException("Digest algorithm not supported: " + algorithm);
            }
        }

        private byte[] ha1(StringBuilder sb, MessageDigest md) {
            // if algorithm is "MD5" or is unspecified => A1 = username ":" realm-value ":"
            // passwd
            // if algorithm is "MD5-sess" => A1 = MD5( username-value ":" realm-value ":"
            // passwd ) ":" nonce-value ":" cnonce-value

            sb.append(principal).append(':').append(realmName).append(':').append(password);
            byte[] core = digestFromRecycledStringBuilder(sb, md, digestCharset);

            if (algorithm == null || "MD5".equalsIgnoreCase(algorithm) || "SHA-256".equalsIgnoreCase(algorithm) || "SHA-512-256".equalsIgnoreCase(algorithm)) {
                // A1 = username ":" realm-value ":" passwd
                return core;
            }
            if ("MD5-sess".equalsIgnoreCase(algorithm) || "SHA-256-sess".equalsIgnoreCase(algorithm) || "SHA-512-256-sess".equalsIgnoreCase(algorithm)) {
                // A1 = HASH(username ":" realm-value ":" passwd ) ":" nonce ":" cnonce
                appendBase16(sb, core);
                sb.append(':').append(nonce).append(':').append(cnonce);
                return digestFromRecycledStringBuilder(sb, md, digestCharset);
            }
            throw new UnsupportedOperationException("Digest algorithm not supported: " + algorithm);
        }

        private byte[] ha2(StringBuilder sb, String digestUri, MessageDigest md) {

            // if qop is "auth" or is unspecified => A2 = Method ":" digest-uri-value
            // if qop is "auth-int" => A2 = Method ":" digest-uri-value ":" H(entity-body)
            sb.append(methodName).append(':').append(digestUri);
            if ("auth-int".equals(qop)) {
                sb.append(':');
                if (entityBodyHash != null) {
                    sb.append(entityBodyHash);
                } else {
                    // Hash of empty body using the current algorithm. Must append in place:
                    // toHexString() would take the same recycled builder and reset it, dropping
                    // the method and digest-uri already written above.
                    appendBase16(sb, md.digest());
                }
            } else if (qop != null && !"auth".equals(qop)) {
                throw new UnsupportedOperationException("Digest qop not supported: " + qop);
            }

            return digestFromRecycledStringBuilder(sb, md, digestCharset);
        }

        private void appendMiddlePart(StringBuilder sb) {
            // request-digest = MD5(H(A1) ":" nonce ":" nc ":" cnonce ":" qop ":" H(A2))
            sb.append(':').append(nonce).append(':');
            if ("auth".equals(qop) || "auth-int".equals(qop)) {
                sb.append(nc).append(':').append(cnonce).append(':').append(qop).append(':');
            }
        }

        private void newResponse(MessageDigest md) {
            // when using preemptive auth, the request uri is missing
            if (uri != null) {
                // BEWARE: compute first as it uses the cached StringBuilder
                String digestUri = AuthenticatorUtils.computeRealmURI(uri, useAbsoluteURI, omitQuery);

                StringBuilder sb = StringBuilderPool.DEFAULT.stringBuilder();

                // WARNING: DON'T MOVE, BUFFER IS RECYCLED!!!!
                byte[] ha1 = ha1(sb, md);
                byte[] ha2 = ha2(sb, digestUri, md);

                appendBase16(sb, ha1);
                appendMiddlePart(sb);
                appendBase16(sb, ha2);

                byte[] responseDigest = digestFromRecycledStringBuilder(sb, md, digestCharset);
                response = toHexString(responseDigest);
            }
        }

        /**
         * Build a {@link Realm}
         *
         * @return a {@link Realm}
         */
        public Realm build() {

            // Avoid generating
            if (isNonEmpty(nonce)) {
                // Defensive: if algorithm is null, default to MD5
                String algo = (algorithm != null) ? algorithm : "MD5";
                MessageDigest md = getDigestInstance(algo);
                newCnonce();
                newResponse(md);
            }

            return new Realm(scheme,
                    principal,
                    password,
                    realmName,
                    nonce,
                    algorithm,
                    response,
                    opaque,
                    qop,
                    nc,
                    cnonce,
                    uri,
                    usePreemptive,
                    (scheme == AuthScheme.DIGEST ? digestCharset : charset),
                    ntlmDomain,
                    ntlmHost,
                    useAbsoluteURI,
                    omitQuery,
                    servicePrincipalName,
                    useCanonicalHostname,
                    customLoginConfig,
                    loginContextName,
                    stale,
                    userhash,
                    sid,
                    maxIterationCount);
        }
    }
}
