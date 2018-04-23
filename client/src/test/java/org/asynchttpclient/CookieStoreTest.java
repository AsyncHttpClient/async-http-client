/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
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

package org.asynchttpclient;

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import org.asynchttpclient.cookie.CookieStore;
import org.asynchttpclient.cookie.ThreadSafeCookieStore;
import org.asynchttpclient.uri.Uri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertTrue;

public class CookieStoreTest {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  @BeforeClass(alwaysRun = true)
  public void setUpGlobal() {
    logger.info("Local HTTP server started successfully");
    System.out.println("--Start");
  }

  @AfterClass(alwaysRun = true)
  public void tearDownGlobal() {
    System.out.println("--Stop");
  }

  @Test
  public void runAllSequentiallyBecauseNotThreadSafe() {
    addCookieWithEmptyPath();
    dontReturnCookieForAnotherDomain();
    returnCookieWhenItWasSetOnSamePath();
    returnCookieWhenItWasSetOnParentPath();
    dontReturnCookieWhenDomainMatchesButPathIsDifferent();
    dontReturnCookieWhenDomainMatchesButPathIsParent();
    returnCookieWhenDomainMatchesAndPathIsChild();
    returnCookieWhenItWasSetOnSubdomain();
    replaceCookieWhenSetOnSameDomainAndPath();
    dontReplaceCookiesWhenTheyHaveDifferentName();
    expireCookieWhenSetWithDateInThePast();
    cookieWithSameNameMustCoexistIfSetOnDifferentDomains();
    handleMissingDomainAsRequestHost();
    handleMissingPathAsSlash();
    returnTheCookieWheniTSissuedFromRequestWithSubpath();
    handleMissingPathAsRequestPathWhenFromRootDir();
    handleMissingPathAsRequestPathWhenPathIsNotEmpty();
    handleDomainInCaseInsensitiveManner();
    handleCookieNameInCaseInsensitiveManner();
    handleCookiePathInCaseSensitiveManner();
    ignoreQueryParametersInUri();
    shouldServerOnSubdomainWhenDomainMatches();
    replaceCookieWhenSetOnSamePathBySameUri();
    handleMultipleCookieOfSameNameOnDifferentPaths();
    handleTrailingSlashesInPaths();
    returnMultipleCookiesEvenIfTheyHaveSameName();
    shouldServeCookiesBasedOnTheUriScheme();
    shouldAlsoServeNonSecureCookiesBasedOnTheUriScheme();
    shouldNotServeSecureCookiesForDefaultRetrievedHttpUriScheme();
    shouldServeSecureCookiesForSpecificallyRetrievedHttpUriScheme();
  }

  private void addCookieWithEmptyPath() {
    CookieStore store = new ThreadSafeCookieStore();
    Uri uri = Uri.create("http://www.foo.com");
    store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; path="));
    assertTrue(store.get(uri).size() > 0);
  }

  private void dontReturnCookieForAnotherDomain() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; path="));
    assertTrue(store.get(Uri.create("http://www.bar.com")).isEmpty());
  }

  private void returnCookieWhenItWasSetOnSamePath() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; path=/bar/"));
    assertTrue(store.get(Uri.create("http://www.foo.com/bar/")).size() == 1);
  }

  private void returnCookieWhenItWasSetOnParentPath() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
    assertTrue(store.get(Uri.create("http://www.foo.com/bar/baz")).size() == 1);
  }

  private void dontReturnCookieWhenDomainMatchesButPathIsDifferent() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
    assertTrue(store.get(Uri.create("http://www.foo.com/baz")).isEmpty());
  }

  private void dontReturnCookieWhenDomainMatchesButPathIsParent() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
    assertTrue(store.get(Uri.create("http://www.foo.com")).isEmpty());
  }

  private void returnCookieWhenDomainMatchesAndPathIsChild() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
    assertTrue(store.get(Uri.create("http://www.foo.com/bar/baz")).size() == 1);
  }

  private void returnCookieWhenItWasSetOnSubdomain() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=.foo.com"));
    assertTrue(store.get(Uri.create("http://bar.foo.com")).size() == 1);
  }

  private void replaceCookieWhenSetOnSameDomainAndPath() {
    CookieStore store = new ThreadSafeCookieStore();
    Uri uri = Uri.create("http://www.foo.com/bar/baz");
    store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
    store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE2; Domain=www.foo.com; path=/bar"));
    assertTrue(store.getAll().size() == 1);
    assertTrue(store.get(uri).get(0).value().equals("VALUE2"));
  }

  private void dontReplaceCookiesWhenTheyHaveDifferentName() {
    CookieStore store = new ThreadSafeCookieStore();
    Uri uri = Uri.create("http://www.foo.com/bar/baz");
    store.add(uri, ClientCookieDecoder.LAX.decode("BETA=VALUE1; Domain=www.foo.com; path=/bar"));
    store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE2; Domain=www.foo.com; path=/bar"));
    assertTrue(store.get(uri).size() == 2);
  }

  private void expireCookieWhenSetWithDateInThePast() {
    CookieStore store = new ThreadSafeCookieStore();
    Uri uri = Uri.create("http://www.foo.com/bar");
    store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
    store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=EXPIRED; Domain=www.foo.com; Path=/bar; Expires=Sun, 06 Nov 1994 08:49:37 GMT"));
    assertTrue(store.getAll().isEmpty());
  }

  private void cookieWithSameNameMustCoexistIfSetOnDifferentDomains() {
    CookieStore store = new ThreadSafeCookieStore();
    Uri uri1 = Uri.create("http://www.foo.com");
    store.add(uri1, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com"));
    Uri uri2 = Uri.create("http://www.bar.com");
    store.add(uri2, ClientCookieDecoder.LAX.decode("ALPHA=VALUE2; Domain=www.bar.com"));

    assertTrue(store.get(uri1).size() == 1);
    assertTrue(store.get(uri1).get(0).value().equals("VALUE1"));

    assertTrue(store.get(uri2).size() == 1);
    assertTrue(store.get(uri2).get(0).value().equals("VALUE2"));
  }

  private void handleMissingDomainAsRequestHost() {
    CookieStore store = new ThreadSafeCookieStore();
    Uri uri = Uri.create("http://www.foo.com");
    store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Path=/"));
    assertTrue(store.get(uri).size() == 1);
  }

  private void handleMissingPathAsSlash() {
    CookieStore store = new ThreadSafeCookieStore();
    Uri uri = Uri.create("http://www.foo.com");
    store.add(uri, ClientCookieDecoder.LAX.decode("tooe_token=0b1d81dd02d207491a6e9b0a2af9470da9eb1dad"));
    assertTrue(store.get(uri).size() == 1);
  }

  private void returnTheCookieWheniTSissuedFromRequestWithSubpath() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE; path=/"));
    assertTrue(store.get(Uri.create("http://www.foo.com")).size() == 1);
  }

  private void handleMissingPathAsRequestPathWhenFromRootDir() {
    CookieStore store = new ThreadSafeCookieStore();
    Uri uri = Uri.create("http://www.foo.com");
    store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1"));
    assertTrue(store.get(uri).size() == 1);
  }

  private void handleMissingPathAsRequestPathWhenPathIsNotEmpty() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
    assertTrue(store.get(Uri.create("http://www.foo.com/baz")).isEmpty());
  }

  // RFC 2965 sec. 3.3.3
  private void handleDomainInCaseInsensitiveManner() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1"));
    assertTrue(store.get(Uri.create("http://www.FoO.com/bar")).size() == 1);
  }

  // RFC 2965 sec. 3.3.3
  private void handleCookieNameInCaseInsensitiveManner() {
    CookieStore store = new ThreadSafeCookieStore();
    Uri uri = Uri.create("http://www.foo.com/bar/baz");
    store.add(uri, ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/bar"));
    store.add(uri, ClientCookieDecoder.LAX.decode("alpha=VALUE2; Domain=www.foo.com; path=/bar"));
    assertTrue(store.getAll().size() == 1);
    assertTrue(store.get(uri).get(0).value().equals("VALUE2"));
  }

  // RFC 2965 sec. 3.3.3
  private void handleCookiePathInCaseSensitiveManner() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com/foo/bar"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1"));
    assertTrue(store.get(Uri.create("http://www.FoO.com/Foo/bAr")).isEmpty());
  }

  private void ignoreQueryParametersInUri() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com/bar?query1"), ClientCookieDecoder.LAX.decode("ALPHA=VALUE1; Domain=www.foo.com; path=/"));
    assertTrue(store.get(Uri.create("http://www.foo.com/bar?query2")).size() == 1);
  }

  // RFC 6265, 5.1.3.  Domain Matching
  private void shouldServerOnSubdomainWhenDomainMatches() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("https://x.foo.org/"), ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/; Domain=foo.org;"));
    assertTrue(store.get(Uri.create("https://y.x.foo.org/")).size() == 1);
  }

  // NOTE: Similar to replaceCookieWhenSetOnSameDomainAndPath()
  private void replaceCookieWhenSetOnSamePathBySameUri() {
    CookieStore store = new ThreadSafeCookieStore();
    Uri uri = Uri.create("https://foo.org/");
    store.add(uri, ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/"));
    store.add(uri, ClientCookieDecoder.LAX.decode("cookie1=VALUE2; Path=/"));
    store.add(uri, ClientCookieDecoder.LAX.decode("cookie1=VALUE3; Path=/"));
    assertTrue(store.getAll().size() == 1);
    assertTrue(store.get(uri).get(0).value().equals("VALUE3"));
  }

  private void handleMultipleCookieOfSameNameOnDifferentPaths() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://www.foo.com"), ClientCookieDecoder.LAX.decode("cookie=VALUE0; path=/"));
    store.add(Uri.create("http://www.foo.com/foo/bar"), ClientCookieDecoder.LAX.decode("cookie=VALUE1; path=/foo/bar/"));
    store.add(Uri.create("http://www.foo.com/foo/baz"), ClientCookieDecoder.LAX.decode("cookie=VALUE2; path=/foo/baz/"));

    Uri uri1 = Uri.create("http://www.foo.com/foo/bar/");
    List<Cookie> cookies1 = store.get(uri1);
    assertTrue(cookies1.size() == 2);
    assertTrue(cookies1.stream().filter(c -> c.value().equals("VALUE0") || c.value().equals("VALUE1")).count() == 2);

    Uri uri2 = Uri.create("http://www.foo.com/foo/baz/");
    List<Cookie> cookies2 = store.get(uri2);
    assertTrue(cookies2.size() == 2);
    assertTrue(cookies2.stream().filter(c -> c.value().equals("VALUE0") || c.value().equals("VALUE2")).count() == 2);
  }

  private void handleTrailingSlashesInPaths() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(
            Uri.create("https://vagrant.moolb.com/app/consumer/j_spring_cas_security_check?ticket=ST-5-Q7gzqPpvG3N3Bb02bm3q-llinder-vagrantmgr.moolb.com"),
            ClientCookieDecoder.LAX.decode("JSESSIONID=211D17F016132BCBD31D9ABB31D90960; Path=/app/consumer/; HttpOnly"));
    assertTrue(store.getAll().size() == 1);
    assertTrue(store.get(Uri.create("https://vagrant.moolb.com/app/consumer/")).get(0).value().equals("211D17F016132BCBD31D9ABB31D90960"));
  }

  private void returnMultipleCookiesEvenIfTheyHaveSameName() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("http://foo.com"), ClientCookieDecoder.LAX.decode("JSESSIONID=FOO; Domain=.foo.com"));
    store.add(Uri.create("http://sub.foo.com"), ClientCookieDecoder.LAX.decode("JSESSIONID=BAR; Domain=sub.foo.com"));

    Uri uri1 = Uri.create("http://sub.foo.com");
    List<Cookie> cookies1 = store.get(uri1);
    assertTrue(cookies1.size() == 2);
    assertTrue(cookies1.stream().filter(c -> c.value().equals("FOO") || c.value().equals("BAR")).count() == 2);

    String result = ClientCookieEncoder.LAX.encode(cookies1.get(0), cookies1.get(1));
    assertTrue(result.equals("JSESSIONID=FOO; JSESSIONID=BAR"));
  }

  // rfc6265#section-1 Cookies for a given host are shared  across all the ports on that host
  private void shouldServeCookiesBasedOnTheUriScheme() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("https://foo.org/moodle/"), ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/"));
    store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE2; Path=/"));
    store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE3; Path=/; Secure"));

    Uri uri = Uri.create("https://foo.org/moodle/login");
    assertTrue(store.getAll().size() == 1);
    assertTrue(store.get(uri).get(0).value().equals("VALUE3"));
    assertTrue(store.get(uri).get(0).isSecure());
  }

  // rfc6265#section-1 Cookies for a given host are shared  across all the ports on that host
  private void shouldAlsoServeNonSecureCookiesBasedOnTheUriScheme() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("https://foo.org/moodle/"), ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/"));
    store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE2; Path=/"));
    store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE3; Path=/; HttpOnly"));

    Uri uri = Uri.create("https://foo.org/moodle/login");
    assertTrue(store.getAll().size() == 1);
    assertTrue(store.get(uri).get(0).value().equals("VALUE3"));
    assertTrue(!store.get(uri).get(0).isSecure());
  }

  // rfc6265#section-1 Cookies for a given host are shared  across all the ports on that host
  private void shouldNotServeSecureCookiesForDefaultRetrievedHttpUriScheme() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("https://foo.org/moodle/"), ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/"));
    store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE2; Path=/"));
    store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE3; Path=/; Secure"));

    Uri uri = Uri.create("http://foo.org/moodle/login");
    assertTrue(store.get(uri).isEmpty());
  }

  // rfc6265#section-1 Cookies for a given host are shared  across all the ports on that host
  private void shouldServeSecureCookiesForSpecificallyRetrievedHttpUriScheme() {
    CookieStore store = new ThreadSafeCookieStore();
    store.add(Uri.create("https://foo.org/moodle/"), ClientCookieDecoder.LAX.decode("cookie1=VALUE1; Path=/"));
    store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE2; Path=/"));
    store.add(Uri.create("https://foo.org:443/moodle/login"), ClientCookieDecoder.LAX.decode("cookie1=VALUE3; Path=/; Secure"));

    Uri uri = Uri.create("https://foo.org/moodle/login");
    assertTrue(store.get(uri).size() == 1);
    assertTrue(store.get(uri).get(0).value().equals("VALUE3"));
    assertTrue(store.get(uri).get(0).isSecure());
  }
}
