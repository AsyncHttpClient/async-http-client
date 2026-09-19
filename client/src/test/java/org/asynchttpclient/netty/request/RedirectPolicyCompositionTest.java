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
package org.asynchttpclient.netty.request;

import org.asynchttpclient.RedirectPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A per-request {@link RedirectPolicy} may only ever TIGHTEN the client's. Composition is therefore the union
 * of the two restrictions, resolved independently - never an ordering over the constants.
 * <p>
 * Walking the full cross product is the point. Constants must be APPENDED to stay binary- and
 * serialization-compatible, so the first follow-up anyone asks for lands at the end; a constant such as
 * {@code REFUSE_CROSS_ORIGIN_BODY(false, true)} would sit at the highest ordinal while carrying FEWER
 * restrictions than its predecessor, and any ordinal or {@code compareTo} comparison would then let a
 * per-request override switch the downgrade arm back off. This test fails if anyone reintroduces one.
 */
class RedirectPolicyCompositionTest {

    @Test
    void compositionIsTheUnionOfFlagsOverTheWholeCrossProduct() {
        for (RedirectPolicy clientPolicy : RedirectPolicy.values()) {
            // null is a legitimate request value: it means "defer to the client entirely".
            assertEquals(clientPolicy.refusesInsecureDowngrade(),
                    NettyRequestSender.refusesInsecureDowngrade(clientPolicy, null),
                    clientPolicy + " + null");
            assertEquals(clientPolicy.refusesCrossOriginBodyReplay(),
                    NettyRequestSender.refusesCrossOriginBodyReplay(clientPolicy, null),
                    clientPolicy + " + null");

            for (RedirectPolicy requestPolicy : RedirectPolicy.values()) {
                assertEquals(clientPolicy.refusesInsecureDowngrade() || requestPolicy.refusesInsecureDowngrade(),
                        NettyRequestSender.refusesInsecureDowngrade(clientPolicy, requestPolicy),
                        clientPolicy + " + " + requestPolicy + " (downgrade)");
                assertEquals(
                        clientPolicy.refusesCrossOriginBodyReplay() || requestPolicy.refusesCrossOriginBodyReplay(),
                        NettyRequestSender.refusesCrossOriginBodyReplay(clientPolicy, requestPolicy),
                        clientPolicy + " + " + requestPolicy + " (cross-origin)");
            }
        }
    }

    @Test
    void aRequestCanNeverWeakenTheClientPolicy() {
        for (RedirectPolicy clientPolicy : RedirectPolicy.values()) {
            for (RedirectPolicy requestPolicy : RedirectPolicy.values()) {
                if (clientPolicy.refusesInsecureDowngrade()) {
                    assertTrue(NettyRequestSender.refusesInsecureDowngrade(clientPolicy, requestPolicy),
                            requestPolicy + " must not switch off " + clientPolicy + "'s downgrade arm");
                }
                if (clientPolicy.refusesCrossOriginBodyReplay()) {
                    assertTrue(NettyRequestSender.refusesCrossOriginBodyReplay(clientPolicy, requestPolicy),
                            requestPolicy + " must not switch off " + clientPolicy + "'s cross-origin arm");
                }
            }
        }
    }
}
