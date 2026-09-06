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
package org.asynchttpclient.spnego;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * One cached engine per Kerberos identity: two identities must never share one, and a changed password
 * replaces the engine instead of leaving the old one, and its password, in the cache.
 */
public class SpnegoEngineInstanceTest {

    @Test
    void differentIdentitiesGetDifferentEngines() {
        SpnegoEngine base = engine("id-alice", "pw", "HTTP/a", "A.EXAMPLE", "ctx", null);
        assertNotSame(base, engine("id-alice", "pw", "HTTP/b", "A.EXAMPLE", "ctx", null), "service principal");
        assertNotSame(base, engine("id-alice", "pw", "HTTP/a", "B.EXAMPLE", "ctx", null), "realm");
        assertNotSame(base, engine("id-alice", "pw", "HTTP/a", "A.EXAMPLE", "other", null), "login context");
        assertNotSame(base, SpnegoEngine.instance("id-alice", "pw", "HTTP/a", "A.EXAMPLE", false, null, "ctx"),
                "canonical host name");
        assertNotSame(base, engine("id-alice", "pw", "HTTP/a", "A.EXAMPLE", "ctx", Map.of("keyTab", "a.keytab")),
                "login configuration");
    }

    @Test
    void fieldsCannotBeShiftedAcrossTheirBoundary() {
        assertNotSame(engine("id-ab", "pw", null, null, "c", null), engine("id-a", "pw", null, null, "bc", null));
    }

    @Test
    void theSameIdentityIsCachedWhateverOrderItsConfigWasBuiltIn() {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("keyTab", "b.keytab");
        forward.put("principal", "bob");
        Map<String, String> reverse = new LinkedHashMap<>();
        reverse.put("principal", "bob");
        reverse.put("keyTab", "b.keytab");

        assertSame(engine("id-bob", "pw", null, null, null, forward), engine("id-bob", "pw", null, null, null, reverse));
    }

    @Test
    void aChangedPasswordReplacesTheCachedEngine() {
        SpnegoEngine first = engine("id-carol", "old", null, null, null, null);
        SpnegoEngine rotated = engine("id-carol", "new", null, null, null, null);

        assertNotSame(first, rotated, "the new password must be used");
        assertSame(rotated, engine("id-carol", "new", null, null, null, null));
        assertNotSame(first, engine("id-carol", "old", null, null, null, null), "the old engine is gone");
    }

    private static SpnegoEngine engine(String username, String password, String spn, String realm, String context,
                                       Map<String, String> loginConfig) {
        return SpnegoEngine.instance(username, password, spn, realm, true, loginConfig, context);
    }
}
