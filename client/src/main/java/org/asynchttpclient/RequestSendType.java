/*
 *    Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
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
package org.asynchttpclient;

/**
 * Controls how requests are dispatched to a host that resolves to several IP addresses.
 *
 * <p>Configured globally through {@link AsyncHttpClientConfig#getRequestSendType()}.
 */
public enum RequestSendType {

    /**
     * Default behavior. The address list returned by DNS is used in its natural order: a new
     * connection always targets the first address and only falls back to the next one when a TCP
     * connection attempt fails. Combined with connection pooling (keyed by host), this means that
     * with keep-alive enabled essentially all traffic to a host stays on the first reachable IP.
     *
     * <p>To spread traffic across a host's IPs in this mode, configure a resolver that rotates its
     * results, such as {@link io.netty.resolver.RoundRobinInetAddressResolver}; the client keeps
     * targeting the first address, and the resolver is what varies which IP that is.
     */
    DEFAULT,

    /**
     * Strict per-request round-robin across the host's resolved IPs.
     *
     * <p>For each request, the client rotates which resolved IP is targeted first (TCP failover to
     * the remaining IPs is preserved) and makes connection reuse IP-aware, so that pooled HTTP/1.1
     * connections and multiplexed HTTP/2 connections are kept and reused per IP rather than per
     * host. The net effect is that consecutive requests to a multi-IP host are spread evenly across
     * all of its addresses, even when connections are kept alive.
     *
     * <p>Notes:
     * <ul>
     *   <li>Has no effect for hosts that resolve to a single address, literal-IP hosts, requests
     *       with an explicit {@link Request#getAddress() address}, or requests routed through a
     *       proxy (HTTP or SOCKS) — the socket is established to the proxy, not directly to the
     *       rotated target IPs. (Round-robin still applies when the proxy is bypassed for the host.)</li>
     *   <li>Connection limits ({@code maxConnectionsPerHost}) remain per host, not per IP.</li>
     *   <li>The address order comes straight from the configured
     *       {@link io.netty.resolver.InetNameResolver}; this mode does not re-sort it. For the
     *       rotation to map consistently across requests, use a resolver that returns the addresses
     *       in a stable order and does not deliberately reorder them between resolutions (for
     *       example {@link io.netty.resolver.dns.DnsNameResolver}). Do not pair this mode with a
     *       resolver that intentionally rotates its results, such as
     *       {@link io.netty.resolver.RoundRobinInetAddressResolver} — that one is meant for
     *       {@link #DEFAULT} mode, where it provides the spreading instead.</li>
     *   <li>Rotation is not health-aware: it always cycles through every IP the resolver returns, so
     *       a temporarily unreachable IP keeps receiving its share of requests — each then retried
     *       via TCP failover to a healthy IP — until the resolver stops returning it. Which IPs are
     *       live is expected to be governed at the DNS/resolver level, as it already is in
     *       {@link #DEFAULT} mode.</li>
     * </ul>
     */
    ROUND_ROBIN
}
