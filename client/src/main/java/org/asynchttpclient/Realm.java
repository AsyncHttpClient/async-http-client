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
import org.asynchttpclient.util.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
    // MD5("")
    private static final String EMPTY_ENTITY_MD5 = "d41d8cd98f00b204e9800998ecf8427e";

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
                  @Nullable String loginContextName) {

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

    @Override
    public String toString() {
        return "Realm{" +
                "principal='" + principal + '\'' +
                ", password='" + password + '\'' +
                ", scheme=" + scheme +
                ", realmName='" + realmName + '\'' +
                ", nonce='" + nonce + '\'' +
                ", algorithm='" + algorithm + '\'' +
                ", response='" + response + '\'' +
                ", opaque='" + opaque + '\'' +
                ", qop='" + qop + '\'' +
                ", nc='" + nc + '\'' +
                ", cnonce='" + cnonce + '\'' +
                ", uri=" + uri +
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
        BASIC, DIGEST, NTLM, SPNEGO, KERBEROS
    }

    /**
     * A builder for {@link Realm}
     */
    public static class Builder {

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
        private static Charset charset = UTF_8;
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

        private static @Nullable String parseRawQop(String rawQop) {
            String[] rawServerSupportedQops = rawQop.split(",");
            String[] serverSupportedQops = new String[rawServerSupportedQops.length];
            for (int i = 0; i < rawServerSupportedQops.length; i++) {
                serverSupportedQops[i] = rawServerSupportedQops[i].trim();
            }

            // prefer auth over auth-int
            for (String rawServerSupportedQop : serverSupportedQops) {
                if ("auth".equals(rawServerSupportedQop)) {
                    return rawServerSupportedQop;
                }
            }

            for (String rawServerSupportedQop : serverSupportedQops) {
                if ("auth-int".equals(rawServerSupportedQop)) {
                    return rawServerSupportedQop;
                }
            }

            return null;
        }

        public Builder parseWWWAuthenticateHeader(String headerLine) {
            setRealmName(match(headerLine, "realm"))
                    .setNonce(match(headerLine, "nonce"))
                    .setOpaque(match(headerLine, "opaque"))
                    .setScheme(isNonEmpty(nonce) ? AuthScheme.DIGEST : AuthScheme.BASIC);
            String algorithm = match(headerLine, "algorithm");
            String cs = match(headerLine, "charset");
            if ("UTF-8".equalsIgnoreCase(cs)) {
                this.digestCharset = UTF_8;
            }
            if (isNonEmpty(algorithm)) {
                setAlgorithm(algorithm);
            }

            // FIXME qop is different with proxy?
            String rawQop = match(headerLine, "qop");
            if (rawQop != null) {
                setQop(parseRawQop(rawQop));
            }

            return this;
        }

        public Builder parseProxyAuthenticateHeader(String headerLine) {
            setRealmName(match(headerLine, "realm"))
                    .setNonce(match(headerLine, "nonce"))
                    .setOpaque(match(headerLine, "opaque"))
                    .setScheme(isNonEmpty(nonce) ? AuthScheme.DIGEST : AuthScheme.BASIC);
            String algorithm = match(headerLine, "algorithm");
            if (isNonEmpty(algorithm)) {
                setAlgorithm(algorithm);
            }
            // FIXME qop is different with proxy?
            setQop(match(headerLine, "qop"));

            return this;
        }

        /**
         * Extracts the value of a token from a WWW-Authenticate or Proxy-Authenticate header line.
         * Example: match('Digest realm="test", nonce="abc"', "realm") returns "test"
         */
        private static @Nullable String match(String headerLine, String token) {
            if (headerLine == null || token == null) return null;
            String pattern = token + "=\"";
            int start = headerLine.indexOf(pattern);
            if (start == -1) return null;
            start += pattern.length();
            int end = headerLine.indexOf('"', start);
            if (end == -1) return null;
            return headerLine.substring(start, end);
        }

        private void newCnonce(MessageDigest md) {
            byte[] b = new byte[8];
            ThreadLocalRandom.current().nextBytes(b);
            byte[] full = md.digest(b);
            // trim to first 8 bytes → 16 hex chars
            byte[] small = Arrays.copyOf(full, Math.min(8, full.length));
            cnonce = toHexString(small);
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
                // when qop == "auth-int", A2 = Method ":" digest-uri-value ":" H(entity-body)
                // but we don't have the request body here
                // we would need a new API
                sb.append(':').append(EMPTY_ENTITY_MD5);

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
                newCnonce(md);
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
                    loginContextName);
        }
    }
}
