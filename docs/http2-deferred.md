# HTTP/2 stabilization — deferred items

A full-depth cold audit of the HTTP/2 work on this branch (5 parallel specialist reviews of the
committed `main...HEAD` diff, each finding adversarially re-verified) produced the fixes that landed
in this commit. This file records the findings that were **deliberately not shipped**, with the
reasoning — the place to look when deciding whether any of them is worth a follow-up.

Nothing here is a regression introduced by this branch; each is either pre-existing, out of scope for
HTTP/2 stabilization, an enhancement whose risk outweighs the stability it would add, or a finding
that adversarial verification refuted.

## Refuted by verification (not a bug)

- **GOAWAY above `lastStreamId` "hangs" the request.** A later review claimed a request on a stream id
  above a received GOAWAY's `lastStreamId` is only notified via a per-stream `Http2GoAwayFrame` user
  event (which `Http2Handler` ignores) and therefore hangs to the request timeout. Refuted by
  decompiling Netty 4.2.15: the connection decoder calls `DefaultHttp2Connection.goAwayReceived`, which
  `closeStreamsGreaterThanLastKnownStreamId` → `stream.close()` → the child `AbstractHttp2StreamChannel`
  fires `channelInactive` → `Http2Handler.handleChannelInactive` → `streamFailed`. The request is failed
  **promptly**, not hung. (The user event is an additional, ignored notification, not the only one.)

## Pre-existing, narrow, left as-is

- **Stacked `Content-Encoding` (e.g. `gzip, deflate`).** `Http2ContentDecompressor` decodes a single
  coding and strips the whole `Content-Encoding` header. Multi-coding responses would be mis-decoded.
  Pre-existing (this branch only added the bomb guard around the existing logic); stacked codings are
  rare in practice. Fixing it (chain decoders in reverse order) is a separate enhancement.
- **`Connection`-listed field names not removed (RFC 9113 §8.2.2).** The named connection-specific
  fields (`Connection`, `Keep-Alive`, `Proxy-Connection`, `Transfer-Encoding`, `Upgrade`, and `TE`
  unless `trailers`) **are** stripped; what is not done is parsing a `Connection` header's *value* to
  drop the fields it enumerates. Netty's own encoder does not do this either, and servers tolerate
  stray tokens; low impact.
- **`OPTIONS *` sends `:path=/` not `:path=*`.** The URI model does not represent asterisk-form, so
  `Uri.toRelativeUrl()` coerces an empty path to `/`. Rare; pre-existing.

## Enhancements (risk/scope outweighs the stability gained now)

- **Blocking source reads on the event loop.** `Http2BodyWriter` reads each upload chunk
  (`InputStream.read` / file read) on the stream's event loop. This is the same model HTTP/1.1 uses
  (`ChunkedWriteHandler` reads on the loop), so it is **not a regression**; the only difference is
  blast radius (an H2 connection multiplexes siblings on that loop). Properly offloading blocking
  reads to an executor is a sizeable change with its own correctness surface. The class Javadoc states
  the actual in-flight bound (one pending chunk plus the channel's write high-water mark, not O(chunk));
  consider offloading the blocking read as a follow-up.
- **No reactive response backpressure (`AUTO_READ` not coupled to `onBodyPartReceived`).** A fast
  server can outrun a slow consumer. HTTP/1.1 behaves the same way, so this is not an H1↔H2
  divergence; it is a cross-cutting enhancement.
- **Dead-but-active pooled-connection health gate.** `Http2PingHandler` is `ALL_IDLE`-only and there
  is no health probe before reusing a pooled H2 connection. A connection the peer silently dropped can
  swallow one request before the failure is observed (then the standard close/abort path runs).
  Enhancement.
- **Server-unprocessed requests are failed, not retried (GOAWAY above `lastStreamId`, RST
  `REFUSED_STREAM`).** Such requests are failed promptly (see the refuted "hang" note above), and the
  server has provably not processed them, so RFC 9113 §6.8/§8.7 permit a safe automatic retry on a new
  connection — which HTTP/1.1 does on a connection drop. HTTP/2 here aborts instead. This is safe (no
  double-send) but is an H1↔H2 behavioral gap; auto-retrying these is a deliberate follow-up because it
  must be scoped precisely to the provably-unprocessed cases.

## Implemented in this commit (formerly deferred)

- **Stream-open failure no longer closes the multiplexed parent.** The open-failure branch in
  `NettyRequestSender.openHttp2Stream` now fails only its own future (`future.abort`) instead of
  `abort(parentChannel, …)`, which would `closeChannel` a parent that may be healthy with sibling
  streams (a SETTINGS race can make Netty reject one stream on an otherwise-fine connection). This
  matches the stream-scoped philosophy of the draining/queued paths and `Http2Handler.streamFailed`
  (`close=false`). A genuinely broken parent is reaped by the next request's normal close path / idle
  timeout; the previously-noted rare redundant-connection linger is accepted over the blast radius.
- **`pendingOpeners` queue now has a hard cap.** `Http2ConnectionState.MAX_PENDING_OPENERS` (10 000)
  bounds the queue; past it `offerPendingOpener` rejects and the caller fast-fails the request, so a
  peer that accepts the connection but never grants stream slots (`SETTINGS_MAX_CONCURRENT_STREAMS=0`,
  or a small limit with never-completed streams) can no longer grow client heap without bound.
- **Client-side Rapid Reset (CVE-2023-44487) RST limiter.** Netty enables the per-window RST limit only
  for servers. On the client a server RST flood is cheap to absorb (one stream-scoped failure per
  in-flight stream, no amplification, no way for a server to force the client to open streams), so
  there is no concrete vulnerability — `decoderEnforceMaxRstFramesPerWindow` on the client codec would
  be pure defense-in-depth.
- **Observability on the queue/orphan paths.** Additional debug/metrics around stream-slot acquisition,
  queueing, and orphan handling would aid diagnosis. Cosmetic.
