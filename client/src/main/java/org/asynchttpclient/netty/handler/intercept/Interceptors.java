/*
 *    Copyright (c) 2015-2023 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient.netty.handler.intercept;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.AsyncHandler;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.request.NettyRequest;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.scram.ScramContext;
import org.asynchttpclient.scram.ScramMessageParser;
import org.asynchttpclient.scram.ScramState;
import org.asynchttpclient.util.AuthenticatorUtils;
import org.asynchttpclient.util.NonceCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.SET_COOKIE;
import static org.asynchttpclient.Dsl.realm;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.CONTINUE_100;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.OK_200;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.PROXY_AUTHENTICATION_REQUIRED_407;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.UNAUTHORIZED_401;

public class Interceptors {

    private static final Logger LOGGER = LoggerFactory.getLogger(Interceptors.class);

    private final AsyncHttpClientConfig config;
    private final Unauthorized401Interceptor unauthorized401Interceptor;
    private final ProxyUnauthorized407Interceptor proxyUnauthorized407Interceptor;
    private final Continue100Interceptor continue100Interceptor;
    private final Redirect30xInterceptor redirect30xInterceptor;
    private final ConnectSuccessInterceptor connectSuccessInterceptor;
    private final ResponseFiltersInterceptor responseFiltersInterceptor;
    private final boolean hasResponseFilters;
    private final ClientCookieDecoder cookieDecoder;
    private final NonceCounter nonceCounter;
    private final NettyRequestSender requestSender;

    public Interceptors(AsyncHttpClientConfig config,
                        ChannelManager channelManager,
                        NettyRequestSender requestSender) {
        this.config = config;
        this.requestSender = requestSender;
        nonceCounter = new NonceCounter();
        unauthorized401Interceptor = new Unauthorized401Interceptor(config, channelManager, requestSender, nonceCounter);
        proxyUnauthorized407Interceptor = new ProxyUnauthorized407Interceptor(config, channelManager, requestSender, nonceCounter);
        continue100Interceptor = new Continue100Interceptor(requestSender);
        redirect30xInterceptor = new Redirect30xInterceptor(channelManager, config, requestSender);
        connectSuccessInterceptor = new ConnectSuccessInterceptor(channelManager, requestSender);
        responseFiltersInterceptor = new ResponseFiltersInterceptor(config, requestSender);
        hasResponseFilters = !config.getResponseFilters().isEmpty();
        cookieDecoder = config.isUseLaxCookieEncoder() ? ClientCookieDecoder.LAX : ClientCookieDecoder.STRICT;
    }

    public boolean exitAfterIntercept(Channel channel, NettyResponseFuture<?> future, AsyncHandler<?> handler, HttpResponse response,
                                      HttpResponseStatus status, HttpHeaders responseHeaders) throws Exception {

        HttpRequest httpRequest = future.getNettyRequest().getHttpRequest();
        ProxyServer proxyServer = future.getProxyServer();
        int statusCode = response.status().code();
        Request request = future.getCurrentRequest();
        // Use the realm the future is carrying (seeded from the request or client config when the
        // exchange started, and reset to null by Redirect30xInterceptor on a cross-origin or
        // scheme-downgrade redirect). Re-deriving from config.getRealm() here re-attaches the
        // client-wide credentials to a redirect target whose auth was just stripped, leaking them
        // to a different origin that answers 401.
        Realm realm = future.getRealm();
        boolean connectRequest = httpRequest.method() == HttpMethod.CONNECT;

        // This MUST BE called before Redirect30xInterceptor because latter assumes cookie store is already updated
        CookieStore cookieStore = config.getCookieStore();
        // A CONNECT is answered by the proxy over a hop that is still plaintext, so its Set-Cookie was written
        // by the proxy — or by anyone on the path — and not by the origin. The store is keyed on
        // getCurrentRequest().getUri(), which is the ORIGIN's URI throughout the tunnel handshake, so filing
        // it here plants an attacker-chosen cookie against the origin; the very next request carries it back
        // inside TLS. Session fixation and CSRF-token overwrite both follow (CWE-384). Cookies the origin
        // itself sets inside the tunnel arrive on a non-CONNECT exchange and are stored as before.
        if (cookieStore != null && !connectRequest) {
            for (String cookieStr : responseHeaders.getAll(SET_COOKIE)) {
                Cookie c;
                try {
                    c = cookieDecoder.decode(cookieStr);
                } catch (IllegalArgumentException e) {
                    // The decoder throws, rather than returning null, on some input such as a non-ASCII
                    // Domain. Drop the cookie as an unparseable one would be, not the whole exchange.
                    LOGGER.debug("Ignoring malformed Set-Cookie header", e);
                    continue;
                }
                if (c != null) {
                    // Set-Cookie header could be invalid/malformed
                    cookieStore.add(future.getCurrentRequest().getUri(), c);
                }
            }
        }

        if (hasResponseFilters && responseFiltersInterceptor.exitAfterProcessingFilters(channel, future, handler, status, responseHeaders)) {
            return true;
        }

        // A CONNECT is addressed to the proxy, on a socket that is still plaintext until the proxy accepts
        // it. Only the two interceptors that speak to the proxy may answer it: the origin-request ones
        // rebuild the exchange as the origin request on a reused channel, which on a rejected CONNECT means
        // sending the origin request — and its credentials — to the proxy in the clear.
        if (connectRequest) {
            if (statusCode == OK_200) {
                return connectSuccessInterceptor.exitAfterHandlingConnect(channel, future, request, proxyServer);
            }
            if (statusCode == PROXY_AUTHENTICATION_REQUIRED_407) {
                return proxyUnauthorized407Interceptor.exitAfterHandling407(channel, future, response, request, proxyServer, httpRequest);
            }
            LOGGER.debug("Proxy answered CONNECT with {}: no tunnel, delivering the response as-is", statusCode);
            return false;
        }

        if (statusCode == UNAUTHORIZED_401) {
            return unauthorized401Interceptor.exitAfterHandling401(channel, future, response, request, realm, httpRequest);
        }

        if (statusCode == PROXY_AUTHENTICATION_REQUIRED_407) {
            return proxyUnauthorized407Interceptor.exitAfterHandling407(channel, future, response, request, proxyServer, httpRequest);
        }

        if (statusCode == CONTINUE_100) {
            return continue100Interceptor.exitAfterHandling100(channel, future);
        }

        if (Redirect30xInterceptor.isRedirect(statusCode)) {
            return redirect30xInterceptor.exitAfterHandlingRedirect(channel, future, response, request, statusCode, realm);
        }

        // Process Authentication-Info / Proxy-Authentication-Info headers (RFC 7616 Section 3.5).
        // A present-but-invalid rspauth aborts the exchange (mutual-auth failure), so honour the boolean.
        if (realm != null && realm.getScheme() == Realm.AuthScheme.DIGEST
                && processAuthenticationInfo(channel, future, responseHeaders, realm, false)) {
            return true;
        }
        Realm proxyRealm = future.getProxyRealm();
        if (proxyRealm != null && proxyRealm.getScheme() == Realm.AuthScheme.DIGEST
                && processAuthenticationInfo(channel, future, responseHeaders, proxyRealm, true)) {
            return true;
        }

        // Process SCRAM Authentication-Info (RFC 7804 §5)
        if (realm != null && realm.getScheme() == Realm.AuthScheme.SCRAM_SHA_256
                && processScramAuthenticationInfo(channel, future, responseHeaders, "Authentication-Info")) {
            return true;
        }
        if (proxyRealm != null && proxyRealm.getScheme() == Realm.AuthScheme.SCRAM_SHA_256
                && processScramAuthenticationInfo(channel, future, responseHeaders, "Proxy-Authentication-Info")) {
            return true;
        }

        return false;
    }

    /**
     * @return true if the exchange failed server verification and the request has been aborted, in which
     * case the caller must stop delivering the response as a success.
     */
    private boolean processAuthenticationInfo(Channel channel, NettyResponseFuture<?> future, HttpHeaders responseHeaders,
                                              Realm currentRealm, boolean proxy) {
        String headerName = proxy ? "Proxy-Authentication-Info" : "Authentication-Info";
        String authInfoHeader = responseHeaders.get(headerName);
        if (authInfoHeader == null) {
            // RFC 7616 §3.5: the header is optional and may travel in chunked trailers (not read here), so an
            // absent header is warn-worthy at most, not a failure — matching the SCRAM decision.
            return false;
        }

        String nextnonce = Realm.Builder.matchParam(authInfoHeader, "nextnonce");
        if (nextnonce != null) {
            // Rotate to the new nonce
            String oldNonce = currentRealm.getNonce();
            if (oldNonce != null) {
                nonceCounter.reset(oldNonce);
            }
            Realm newRealm = realm(currentRealm)
                    .setNonce(nextnonce)
                    .setNc("00000001")
                    .build();
            if (proxy) {
                future.setProxyRealm(newRealm);
            } else {
                future.setRealm(newRealm);
            }
            LOGGER.debug("Rotated to nextnonce from {} header", headerName);
        }

        // rspauth signs the request the client just sent, so it must be verified against the parameters
        // that were actually on the wire — uri, nonce, nc, cnonce, qop and realm — not against the realm
        // the future is carrying. That realm is rebuilt for header emission (regenerating its cnonce), its
        // uri is the one the exchange started with rather than the one a redirect moved to, and on a
        // preemptive first request it has no uri at all. Recover them from the request's own
        // Authorization header and use currentRealm only for the shared secret and charset.
        String rspauth = Realm.Builder.matchParam(authInfoHeader, "rspauth");
        if (rspauth != null) {
            String sentCredentials = sentDigestCredentials(future, proxy);
            String expectedRspauth = sentCredentials != null
                    ? AuthenticatorUtils.computeExpectedRspAuth(currentRealm, sentCredentials)
                    : null;
            if (expectedRspauth == null) {
                // Nothing to compare against: no Digest credentials were sent (a CONNECT is answered before
                // any are), or the ones that were cannot be parsed back. Enforcing a value known to be
                // wrong would fail honest servers, which is worse than not enforcing here.
                LOGGER.warn("Can't verify the rspauth in {}: the Digest credentials sent with this request "
                        + "could not be recovered, so mutual authentication is not enforced for it", headerName);
                return false;
            }
            if (!rspauth.equalsIgnoreCase(expectedRspauth)) {
                // RFC 7616 §3.5: a present-but-invalid rspauth means the server failed to prove knowledge of
                // the shared secret, so the client must not treat the response as an authenticated success.
                // Mirror the SCRAM ServerSignature handling (#2235): abort the request.
                LOGGER.warn("Server rspauth mismatch in {}, aborting request "
                        + "(RFC 7616 section 3.5: server failed mutual authentication)", headerName);
                requestSender.abort(channel, future, new IOException("Digest rspauth verification failed"));
                return true;
            }
            LOGGER.debug("Digest rspauth verified successfully from {} header", headerName);
        }
        return false;
    }

    /**
     * The Digest credentials actually sent on the request whose response we are processing, read back from
     * that request's own {@code Authorization} (or {@code Proxy-Authorization}) header. These are the
     * parameters the server signed the rspauth over; the realm on the future is not, because it is rebuilt
     * for header emission and only ever holds the state of the exchange that seeded it.
     *
     * @return the header value, or {@code null} when the request carried no credentials — a CONNECT sent to
     * open a tunnel is one, since the origin credentials are deliberately withheld from the proxy
     */
    private static String sentDigestCredentials(NettyResponseFuture<?> future, boolean proxy) {
        NettyRequest nettyRequest = future.getNettyRequest();
        if (nettyRequest == null) {
            return null;
        }
        return nettyRequest.getHttpRequest().headers().get(proxy ? PROXY_AUTHORIZATION : AUTHORIZATION);
    }

    /**
     * @return true if the exchange failed server verification and the request has been aborted, in which
     * case the caller must stop delivering the response as a success.
     */
    private boolean processScramAuthenticationInfo(Channel channel, NettyResponseFuture<?> future, HttpHeaders responseHeaders,
                                                   String headerName) {
        ScramContext ctx = future.getScramContext();
        if (ctx == null || ctx.getState() != ScramState.CLIENT_FINAL_SENT) {
            return false;
        }

        String authInfo = responseHeaders.get(headerName);
        if (authInfo == null) {
            // RFC 7804 §6: may be in chunked trailers (not supported by AHC). We can't tell an honest
            // server that used trailers from a stripped header, so leave the response untouched here.
            LOGGER.warn("SCRAM: response without {} header; "
                    + "ServerSignature cannot be verified (may be in chunked trailers)", headerName);
            return false;
        }

        String data = Realm.Builder.matchParam(authInfo, "data");
        if (data == null) {
            LOGGER.warn("SCRAM: Authentication-Info header missing data attribute");
            return false;
        }

        String serverFinalMsg;
        try {
            serverFinalMsg = new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("SCRAM: invalid base64 in {} data attribute: {}", headerName, e.getMessage());
            ctx.setState(ScramState.FAILED);
            return abortScram(channel, future, "SCRAM: unparseable ServerSignature in " + headerName);
        }

        // verifyServerFinal sets state to AUTHENTICATED or FAILED internally
        if (ctx.verifyServerFinal(serverFinalMsg)) {
            LOGGER.debug("SCRAM ServerSignature verified successfully");
            return false;
        }
        // RFC 7804 §5: a present-but-invalid ServerSignature means the peer failed to prove knowledge of
        // the shared secret, so the client MUST consider the exchange unsuccessful rather than hand the
        // caller a response from an unauthenticated peer.
        return abortScram(channel, future, "SCRAM ServerSignature verification failed");
    }

    private boolean abortScram(Channel channel, NettyResponseFuture<?> future, String message) {
        LOGGER.warn("{} — aborting request (RFC 7804 §5: MUST consider unsuccessful)", message);
        requestSender.abort(channel, future, new IOException(message));
        return true;
    }
}
