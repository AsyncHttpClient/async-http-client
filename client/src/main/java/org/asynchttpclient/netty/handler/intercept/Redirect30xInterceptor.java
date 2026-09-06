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
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.AsciiString;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.handler.MaxRedirectException;
import org.asynchttpclient.handler.RedirectRefusedException;
import org.asynchttpclient.handler.RedirectRefusedException.Reason;
import org.asynchttpclient.netty.NettyResponseFuture;
import org.asynchttpclient.netty.channel.ChannelManager;
import org.asynchttpclient.netty.channel.PrincipalScopedPartitionKey;
import org.asynchttpclient.netty.request.NettyRequestSender;
import org.asynchttpclient.request.body.generator.FileBodyGenerator;
import org.asynchttpclient.request.body.generator.InputStreamBodyGenerator;
import org.asynchttpclient.request.body.multipart.InputStreamPart;
import org.asynchttpclient.request.body.multipart.Part;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpHeaderNames.LOCATION;
import static io.netty.handler.codec.http.HttpHeaderNames.PROXY_AUTHORIZATION;
import static org.asynchttpclient.uri.Uri.HTTP;
import static org.asynchttpclient.uri.Uri.HTTPS;
import static org.asynchttpclient.uri.Uri.WS;
import static org.asynchttpclient.uri.Uri.WSS;
import static org.asynchttpclient.util.HttpConstants.Methods.GET;
import static org.asynchttpclient.util.HttpConstants.Methods.HEAD;
import static org.asynchttpclient.util.HttpConstants.Methods.OPTIONS;
import static org.asynchttpclient.util.HttpConstants.Methods.POST;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.FOUND_302;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.MOVED_PERMANENTLY_301;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.PERMANENT_REDIRECT_308;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.SEE_OTHER_303;
import static org.asynchttpclient.util.HttpConstants.ResponseStatusCodes.TEMPORARY_REDIRECT_307;
import static org.asynchttpclient.util.HttpUtils.followRedirect;
import static org.asynchttpclient.util.MiscUtils.isEmpty;
import static org.asynchttpclient.util.ThrowableUtil.unknownStackTrace;

public class Redirect30xInterceptor {

    public static final Set<Integer> REDIRECT_STATUSES = new HashSet<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(Redirect30xInterceptor.class);

    static {
        REDIRECT_STATUSES.add(MOVED_PERMANENTLY_301);
        REDIRECT_STATUSES.add(FOUND_302);
        REDIRECT_STATUSES.add(SEE_OTHER_303);
        REDIRECT_STATUSES.add(TEMPORARY_REDIRECT_307);
        REDIRECT_STATUSES.add(PERMANENT_REDIRECT_308);
    }

    /**
     * Whether {@code statusCode} is a redirect this interceptor follows. Only a 3xx can be, and the class
     * check takes an {@code int}, so the boxing {@link #REDIRECT_STATUSES} lookup stays off the 2xx, 4xx
     * and 5xx responses that carry almost all traffic. The set is consulted rather than inlined because it
     * is public and mutable, so an extra 3xx a caller registered is honoured.
     */
    static boolean isRedirect(int statusCode) {
        return HttpStatusClass.REDIRECTION.contains(statusCode) && REDIRECT_STATUSES.contains(statusCode);
    }

    private final ChannelManager channelManager;
    private final AsyncHttpClientConfig config;
    private final NettyRequestSender requestSender;
    private final MaxRedirectException maxRedirectException;
    private final boolean stripAuthorizationOnRedirect;
    private final boolean refuseSchemeDowngradeOnRedirect;
    private final boolean refuseCrossOriginBodyOnRedirect;
    private final ClientCookieDecoder cookieDecoder;

    Redirect30xInterceptor(ChannelManager channelManager, AsyncHttpClientConfig config, NettyRequestSender requestSender) {
        this.channelManager = channelManager;
        this.config = config;
        this.requestSender = requestSender;
        stripAuthorizationOnRedirect = config.isStripAuthorizationOnRedirect(); // New flag
        refuseSchemeDowngradeOnRedirect = config.isRefuseSchemeDowngradeOnRedirect();
        refuseCrossOriginBodyOnRedirect = config.isRefuseCrossOriginBodyOnRedirect();
        cookieDecoder = config.isUseLaxCookieEncoder() ? ClientCookieDecoder.LAX : ClientCookieDecoder.STRICT;
        maxRedirectException = unknownStackTrace(new MaxRedirectException("Maximum redirect reached: " + config.getMaxRedirects()),
                Redirect30xInterceptor.class, "exitAfterHandlingRedirect");
    }

    public boolean exitAfterHandlingRedirect(Channel channel, NettyResponseFuture<?> future, HttpResponse response, Request request,
                                             int statusCode, Realm realm) throws Exception {

        if (followRedirect(config, request)) {
            String location = response.headers().get(LOCATION);
            if (isEmpty(location)) {
                // RFC 9110 section 15.4 only redirects when a Location is provided, and an empty one
                // resolves back to the current URI.
                return false;
            }
            if (future.incrementAndGetCurrentRedirectCount() >= config.getMaxRedirects()) {
                throw maxRedirectException;

            } else {
                // We must allow auth handling again.
                future.setInAuth(false);
                future.setInProxyAuth(false);
                future.setScramContext(null);
                future.tightenRedirectRefusals(request);

                String originalMethod = request.getMethod();
                boolean isPost = originalMethod.equals(POST);
                boolean methodAlreadyPreserved = originalMethod.equals(GET) ||
                        originalMethod.equals(OPTIONS) || originalMethod.equals(HEAD);
                boolean strict302 = statusCode == FOUND_302 && config.isStrict302Handling();
                // RFC 9110 limits the historical 301/302 POST-to-GET rewrite to POST.
                // This also preserves QUERY as required by RFC 10008 section 2.5.
                boolean legacyPostToGet = isPost && (statusCode == MOVED_PERMANENTLY_301 ||
                        (statusCode == FOUND_302 && !strict302));
                boolean switchToGet = !methodAlreadyPreserved &&
                        (statusCode == SEE_OTHER_303 || legacyPostToGet);
                boolean keepBody = statusCode != SEE_OTHER_303 && !switchToGet;

                // Location resolves against the target URI of the request actually sent on this leg
                // (RFC 9110 section 15.4, modification 1), and the gates below must judge that same URI.
                // A 401 or 407 retry leaves the future's own target behind, so do not read it here.
                Uri currentUri = request.getUri();
                Uri newUri = Uri.create(currentUri, location);

                boolean sameBase = currentUri.isSameBase(newUri);
                boolean schemeDowngrade = currentUri.isSecured() && !newUri.isSecured();

                // Refuse before ensureBodyReplayable, whose IOException an IOExceptionFilter would replay.
                if (schemeDowngrade && refuseSchemeDowngrade(future)) {
                    throw new RedirectRefusedException(Reason.SCHEME_DOWNGRADE, statusCode, currentUri, newUri);
                }
                BodyRepresentation bodyRepresentation =
                        keepBody ? selectedBodyRepresentation(request) : BodyRepresentation.NONE;
                if (keepBody && refuseCrossOriginBody(future)
                        && !sameOrigin(currentUri, newUri) && !secureUpgrade(currentUri, newUri)
                        && bodyRepresentation != BodyRepresentation.NONE) {
                    throw new RedirectRefusedException(Reason.CROSS_ORIGIN_BODY, statusCode, currentUri, newUri);
                }

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Redirecting to {}", newUri.toUrlWithoutUserInfo());
                }

                boolean stripAuth = !sameBase || schemeDowngrade || stripAuthorizationOnRedirect;

                if (LOGGER.isDebugEnabled() && stripAuth && (request.getRealm() != null
                        || request.getHeaders().contains(AUTHORIZATION)
                        || request.getHeaders().contains(COOKIE))) {
                    LOGGER.debug("Stripping credentials on redirect to {}", newUri.toUrlWithoutUserInfo());
                }

                final RequestBuilder requestBuilder;
                if (keepBody) {
                    ensureBodyReplayable(request, bodyRepresentation, future.isStreamConsumed());
                    requestBuilder = request.toBuilder();
                    if (!sameBase) {
                        // An explicitly resolved address and virtual host belong to the previous target.
                        requestBuilder.setAddress(null);
                        requestBuilder.setVirtualHost(null);
                    }
                } else {
                    requestBuilder = new RequestBuilder(switchToGet ? GET : originalMethod)
                            .setChannelPoolPartitioning(request.getChannelPoolPartitioning())
                            .setLocalAddress(request.getLocalAddress())
                            .setNameResolver(request.getNameResolver())
                            .setProxyServer(request.getProxyServer())
                            .setRangeOffset(request.getRangeOffset());
                }
                // A same-origin hop keeps the caller's cookies but not the store's: those are its values from
                // before this response and would outrank what it just set. The store adds its current ones below.
                CookieStore cookieStore = config.getCookieStore();
                if (stripAuth) {
                    requestBuilder.resetCookies();
                } else {
                    requestBuilder.setCookies(cookieStore == null
                            ? request.getCookies() : CallerCookies.of(request, response, cookieStore, newUri, cookieDecoder));
                }

                requestBuilder.setMethod(switchToGet ? GET : originalMethod)
                        .setFollowRedirect(true)
                        .setRealm(stripAuth ? null : request.getRealm())
                        .setHeaders(propagatedHeaders(request, realm, keepBody, stripAuth, cookieStore != null))
                        .setRequestTimeout(request.getRequestTimeout())
                        .setReadTimeout(request.getReadTimeout());

                // The exchange holds the deadline flag on its future, so a hop that does not carry it forward
                // leaves the request saying something the exchange is not doing, which is what a filter or a
                // signature calculator reads. Set only when the request has one: the setter takes a primitive,
                // and null is how a request defers to the client config.
                Boolean useAbsoluteRequestDeadline = request.getUseAbsoluteRequestDeadline();
                if (useAbsoluteRequestDeadline != null) {
                    requestBuilder.setUseAbsoluteRequestDeadline(useAbsoluteRequestDeadline);
                }

                // The !keepBody branch builds from an empty builder, so carry these forward or the caller's
                // choice lapses from the second hop on.
                Boolean refuseSchemeDowngrade = request.getRefuseSchemeDowngradeOnRedirect();
                if (refuseSchemeDowngrade != null) {
                    requestBuilder.setRefuseSchemeDowngradeOnRedirect(refuseSchemeDowngrade);
                }
                Boolean refuseCrossOriginBody = request.getRefuseCrossOriginBodyOnRedirect();
                if (refuseCrossOriginBody != null) {
                    requestBuilder.setRefuseCrossOriginBodyOnRedirect(refuseCrossOriginBody);
                }

                // Sampled before stripAuth clears the realm: the channel being drained is authenticated to
                // the previous origin, and reading it afterwards would file it unscoped.
                final Object initialPartitionKey = PrincipalScopedPartitionKey.scope(
                        future.getPartitionKey(), future.getRealm());

                if (stripAuth) {
                    future.setRealm(null);
                    future.setProxyRealm(null);
                }

                // in case of a redirect from HTTP to HTTPS, future
                // attributes might change
                final boolean initialConnectionKeepAlive = future.isKeepAlive();

                if (cookieStore != null) {
                    // Update request's cookies assuming that cookie store is already updated by Interceptors
                    for (Cookie cookie : cookieStore.get(newUri)) {
                        requestBuilder.addCookieIfUnset(cookie);
                    }
                }

                if (sameBase && !keepBody) {
                    // we can only assume the virtual host is still valid if the baseUrl is the same
                    requestBuilder.setVirtualHost(request.getVirtualHost());
                }

                final Request nextRequest = requestBuilder.setUri(newUri).build();
                future.setTargetRequest(nextRequest);

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Sending redirect to {}", newUri.toUrlWithoutUserInfo());
                }

                if (channel instanceof Http2StreamChannel) {
                    // HTTP/2 stream channels are single-use and close immediately after the response.
                    // No draining needed — just close the stream and send the next request.
                    channel.close();
                    requestSender.sendNextRequest(nextRequest, future);
                } else if (future.isKeepAlive() && !HttpUtil.isTransferEncodingChunked(response)) {
                    if (sameBase) {
                        future.setReuseChannel(true);
                        // we can't directly send the next request because we still have to received LastContent
                        requestSender.drainChannelAndExecuteNextRequest(channel, future, nextRequest);
                    } else {
                        channelManager.drainChannelAndOffer(channel, future, initialConnectionKeepAlive, initialPartitionKey);
                        requestSender.sendNextRequest(nextRequest, future);
                    }

                } else {
                    // redirect + chunking = WAT
                    channelManager.closeChannel(channel);
                    requestSender.sendNextRequest(nextRequest, future);
                }

                return true;
            }
        }
        return false;
    }

    // Tightening only, unlike followRedirect: these are security switches, and a framework layer that builds
    // the Request should not be able to void a posture the operator set on the client or on an earlier hop.
    private boolean refuseSchemeDowngrade(NettyResponseFuture<?> future) {
        return refuseSchemeDowngradeOnRedirect || future.isRefuseSchemeDowngradeLatched();
    }

    private boolean refuseCrossOriginBody(NettyResponseFuture<?> future) {
        return refuseCrossOriginBodyOnRedirect || future.isRefuseCrossOriginBodyLatched();
    }

    /**
     * Same scheme, host and effective port, per RFC 6454 section 4. Hosts fold ASCII-only, the
     * {@code i;ascii-casemap} collation that step 5 of that section asks for: {@link String#equalsIgnoreCase}
     * folds Unicode and would call {@code i.example} equal to a host starting {@code U+0130}, a different
     * host. Nothing here does IDNA either, so a Unicode host and its A-label read as different origins,
     * erring towards refusal.
     * <p>
     * Equivalent to {@link Uri#isSameBase(Uri)} today. That one also gates credential stripping and must
     * stay at least as strict as this, so tighten this only by tightening that first.
     */
    static boolean sameOrigin(Uri from, Uri to) {
        return from.getScheme().equals(to.getScheme())
                && AsciiString.contentEqualsIgnoreCase(from.getHost(), to.getHost())
                && from.getExplicitPort() == to.getExplicitPort();
    }

    /**
     * Whether this hop only swaps the same host onto TLS. A deliberate exemption from the origin rule rather
     * than something RFC 6454 allows, because refusing a move onto TLS would cost confidentiality rather than
     * protect it. The host is compared, not trusted; the path and query are not compared at all, so the
     * content lands wherever the redirect says on that host. The port must be unchanged or both sides at
     * their scheme's default, so {@code :8080} to {@code :9999} is a different endpoint and stays refused.
     * That is modelled on the port mapping in RFC 6797 section 8.3 but looser than it: 8.3 is scoped to a
     * Known HSTS Host and we keep no HSTS state, and it would have mapped an explicit {@code :80} to
     * {@code :443} where this accepts {@code :80} to {@code :80}, which is the endpoint the redirect was
     * served from.
     * <p>
     * Only the content arm consults this; the hop is still cross-base, so credentials are stripped as usual
     * and cookies are re-derived from the {@link org.asynchttpclient.cookie.CookieStore} against the new URI
     * - which on an upgraded hop includes the host's {@code Secure} ones.
     */
    static boolean secureUpgrade(Uri from, Uri to) {
        // Named pairs, not isSecured(), which would also admit http to wss. Schemes compare exactly because
        // the Uri constructor lowercases them on every construction path.
        boolean upgraded = HTTP.equals(from.getScheme()) && HTTPS.equals(to.getScheme())
                || WS.equals(from.getScheme()) && WSS.equals(to.getScheme());
        return upgraded
                && AsciiString.contentEqualsIgnoreCase(from.getHost(), to.getHost())
                && (from.getExplicitPort() == to.getExplicitPort()
                        || from.getExplicitPort() == from.getSchemeDefaultPort()
                                && to.getExplicitPort() == to.getSchemeDefaultPort());
    }

    private static void ensureBodyReplayable(Request request, BodyRepresentation bodyRepresentation,
                                             boolean streamConsumed) throws IOException {
        if (bodyRepresentation == BodyRepresentation.BODY_PARTS) {
            for (Part part : request.getBodyParts()) {
                if (part instanceof InputStreamPart) {
                    throw new IOException("Multipart InputStream body part '" + part.getName()
                            + "' cannot be replayed after redirect");
                }
            }
        }

        File file = null;
        if (bodyRepresentation == BodyRepresentation.FILE) {
            file = request.getFile();
        } else if (bodyRepresentation == BodyRepresentation.FILE_BODY_GENERATOR) {
            file = ((FileBodyGenerator) request.getBodyGenerator()).getFile();
        }
        if (file != null && !file.isFile()) {
            throw new IOException("Redirect request body file " + file.getAbsolutePath()
                    + " is not a file or does not exist");
        }

        InputStream inputStream = null;
        if (bodyRepresentation == BodyRepresentation.STREAM_DATA) {
            inputStream = request.getStreamData();
        } else if (bodyRepresentation == BodyRepresentation.INPUT_STREAM_BODY_GENERATOR) {
            inputStream = ((InputStreamBodyGenerator) request.getBodyGenerator()).getInputStream();
        }
        // NettyInputStreamBody alone tracks consumption; multipart must not use this flag.
        // An early redirect before 100 Continue leaves the stream available for its first write.
        if (streamConsumed && inputStream != null && !inputStream.markSupported()) {
            throw new IOException("Redirect request body InputStream does not support mark/reset"
                    + " and cannot be replayed");
        }
    }

    private static BodyRepresentation selectedBodyRepresentation(Request request) {
        // Keep this precedence aligned with NettyRequestFactory.body. Some setters leave lower-priority
        // representations in place, so validate only the body selected for transmission.
        if (request.getByteData() != null) {
            return BodyRepresentation.BYTE_DATA;
        }
        if (request.getCompositeByteData() != null) {
            return BodyRepresentation.COMPOSITE_BYTE_DATA;
        }
        if (request.getStringData() != null) {
            return BodyRepresentation.STRING_DATA;
        }
        if (request.getByteBufferData() != null) {
            return BodyRepresentation.BYTE_BUFFER_DATA;
        }
        if (request.getByteBufData() != null) {
            return BodyRepresentation.BYTE_BUF_DATA;
        }
        if (request.getStreamData() != null) {
            return BodyRepresentation.STREAM_DATA;
        }
        if (!request.getFormParams().isEmpty()) {
            return BodyRepresentation.FORM_PARAMS;
        }
        if (!request.getBodyParts().isEmpty()) {
            return BodyRepresentation.BODY_PARTS;
        }
        if (request.getFile() != null) {
            return BodyRepresentation.FILE;
        }
        if (request.getBodyGenerator() instanceof FileBodyGenerator) {
            return BodyRepresentation.FILE_BODY_GENERATOR;
        }
        if (request.getBodyGenerator() instanceof InputStreamBodyGenerator) {
            return BodyRepresentation.INPUT_STREAM_BODY_GENERATOR;
        }
        return request.getBodyGenerator() == null
                ? BodyRepresentation.NONE
                : BodyRepresentation.BODY_GENERATOR;
    }

    private static boolean selectedBodyHasUnknownLength(Request request) {
        BodyRepresentation bodyRepresentation = selectedBodyRepresentation(request);
        if (bodyRepresentation == BodyRepresentation.STREAM_DATA
                || bodyRepresentation == BodyRepresentation.BODY_GENERATOR) {
            return true;
        }
        if (bodyRepresentation == BodyRepresentation.INPUT_STREAM_BODY_GENERATOR) {
            return ((InputStreamBodyGenerator) request.getBodyGenerator()).getContentLength() < 0;
        }
        return false;
    }

    private enum BodyRepresentation {
        BYTE_DATA,
        COMPOSITE_BYTE_DATA,
        STRING_DATA,
        BYTE_BUFFER_DATA,
        BYTE_BUF_DATA,
        STREAM_DATA,
        FORM_PARAMS,
        BODY_PARTS,
        FILE,
        FILE_BODY_GENERATOR,
        INPUT_STREAM_BODY_GENERATOR,
        BODY_GENERATOR,
        NONE
    }

    private static HttpHeaders propagatedHeaders(Request request, Realm realm, boolean keepBody, boolean stripAuthorization,
                                                 boolean cookiesReconciled) {
        HttpHeaders headers = request.getHeaders().copy().remove(HOST);

        // Preserve an explicit length when the selected stream representation cannot rebuild it.
        if (!keepBody || !selectedBodyHasUnknownLength(request)) {
            headers.remove(CONTENT_LENGTH);
        }

        if (!keepBody) {
            headers.remove(CONTENT_TYPE);
        }

        if (stripAuthorization) {
            // Cookie is dropped only on the security boundary; the URI-scoped CookieStore re-adds
            // any cookies that legitimately match the new target after this method returns.
            headers.remove(AUTHORIZATION)
                    .remove(PROXY_AUTHORIZATION)
                    .remove(COOKIE);
        } else if (realm != null && (realm.getScheme() == AuthScheme.NTLM
                || realm.getScheme() == AuthScheme.SCRAM_SHA_256)) {
            headers.remove(AUTHORIZATION)
                    .remove(PROXY_AUTHORIZATION);
        }
        if (cookiesReconciled) {
            // CallerCookies carries its pairs in the cookie list.
            headers.remove(COOKIE);
        }
        return headers;
    }
}
