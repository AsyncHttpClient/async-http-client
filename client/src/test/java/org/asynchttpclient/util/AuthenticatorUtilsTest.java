/*
 * Copyright (c) 2026 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 *     http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.util;

import org.asynchttpclient.Realm;
import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.asynchttpclient.uri.Uri;
import org.testng.annotations.Test;

import static org.asynchttpclient.util.AuthenticatorUtils.computeRealmURI;
import static org.asynchttpclient.util.AuthenticatorUtils.perRequestAuthorizationHeader;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class AuthenticatorUtilsTest {

  private static final Uri URI_WITH_USERINFO = Uri.create("http://user:secret@example.com:8080/path?q=1");

  /**
   * The Digest {@code uri=} parameter goes on the wire and is covered by the digest itself. RFC 9110
   * section 4.2.4 forbids generating the userinfo subcomponent in a request target, and rendering it here
   * would put the password in the clear on the very hop Digest exists to protect.
   */
  @Test
  public void computeRealmURIAbsoluteDropsUserInfo() {
    assertEquals(computeRealmURI(URI_WITH_USERINFO, true, false), "http://example.com:8080/path?q=1");
  }

  @Test
  public void computeRealmURIAbsoluteOmitQueryDropsUserInfo() {
    // omitQuery rebuilds the Uri via withNewQuery(null), which carries the userinfo over: a second,
    // independent path to the same leak.
    assertEquals(computeRealmURI(URI_WITH_USERINFO, true, true), "http://example.com:8080/path");
  }

  @Test
  public void computeRealmURIAbsoluteWithoutUserInfoIsUnchanged() {
    Uri uri = Uri.create("http://example.com:8080/path?q=1");
    assertEquals(computeRealmURI(uri, true, false), "http://example.com:8080/path?q=1");
    assertEquals(computeRealmURI(uri, true, true), "http://example.com:8080/path");
  }

  @Test
  public void computeRealmURIRelativeIsUnchanged() {
    assertEquals(computeRealmURI(URI_WITH_USERINFO, false, false), "/path?q=1");
    assertEquals(computeRealmURI(URI_WITH_USERINFO, false, true), "/path");
  }

  /**
   * End-to-end over the header the client actually emits: no fragment of the userinfo may appear anywhere
   * in the Authorization value.
   */
  @Test
  public void digestAuthorizationHeaderDoesNotCarryUserInfo() {
    Realm realm = new Realm.Builder("user", "secret")
            .setScheme(Realm.AuthScheme.DIGEST)
            .setUri(URI_WITH_USERINFO)
            .setMethodName("GET")
            .setUsePreemptiveAuth(true)
            .setUseAbsoluteURI(true)
            .setRealmName("realm")
            .setNonce("nonce")
            .build();

    Request request = new RequestBuilder("GET").setUri(URI_WITH_USERINFO).build();
    String header = perRequestAuthorizationHeader(request, realm);

    assertTrue(header.startsWith("Digest "), "expected a Digest header but got: " + header);
    assertTrue(header.contains("uri=\"http://example.com:8080/path?q=1\""),
            "expected a userinfo-free uri parameter but got: " + header);
    assertFalse(header.contains("secret@"), "password must not appear in the Digest header: " + header);
    assertFalse(header.contains("user:secret"), "credentials must not appear in the Digest header: " + header);
  }
}
