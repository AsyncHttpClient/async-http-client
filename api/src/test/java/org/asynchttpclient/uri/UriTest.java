/*
 * Copyright (c) 2014 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.uri;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class UriTest {

    @Test
    public void testSimpleParsing() {
        Uri url = Uri.create("https://graph.facebook.com/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "graph.facebook.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/750198471659552/accounts/test-users");
        assertEquals(url.getQuery(), "method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testRootRelativeURIWithRootContext() {

        Uri context = Uri.create("https://graph.facebook.com");
        
        Uri url = Uri.create(context, "/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
        
        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "graph.facebook.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/750198471659552/accounts/test-users");
        assertEquals(url.getQuery(), "method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }
    
    @Test
    public void testRootRelativeURIWithNonRootContext() {

        Uri context = Uri.create("https://graph.facebook.com/foo/bar");
        
        Uri url = Uri.create(context, "/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
        
        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "graph.facebook.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/750198471659552/accounts/test-users");
        assertEquals(url.getQuery(), "method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }
    
    @Test
    public void testNonRootRelativeURIWithNonRootContext() {

        Uri context = Uri.create("https://graph.facebook.com/foo/bar");
        
        Uri url = Uri.create(context, "750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
        
        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "graph.facebook.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/foo/750198471659552/accounts/test-users");
        assertEquals(url.getQuery(), "method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }
    
    @Test
    public void testAbsoluteURIWithContext() {

        Uri context = Uri.create("https://hello.com/foo/bar");
        
        Uri url = Uri.create(context, "https://graph.facebook.com/750198471659552/accounts/test-users?method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
        
        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "graph.facebook.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/750198471659552/accounts/test-users");
        assertEquals(url.getQuery(), "method=get&access_token=750198471659552lleveCvbUu_zqBa9tkT3tcgaPh4");
    }

    @Test
    public void testRelativeUriWithDots() {
        Uri context = Uri.create("https://hello.com/level1/level2/");

        Uri url = Uri.create(context, "../other/content/img.png");

        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "hello.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/level1/other/content/img.png");
        assertNull(url.getQuery());
    }

    @Test
    public void testRelativeUriWithDotsAboveRoot() {
        Uri context = Uri.create("https://hello.com/level1");

        Uri url = Uri.create(context, "../other/content/img.png");

        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "hello.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/../other/content/img.png");
        assertNull(url.getQuery());
    }

    @Test
    public void testRelativeUriWithAbsoluteDots() {
        Uri context = Uri.create("https://hello.com/level1/");

        Uri url = Uri.create(context, "/../other/content/img.png");

        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "hello.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/../other/content/img.png");
        assertNull(url.getQuery());
    }

    @Test
    public void testRelativeUriWithConsecutiveDots() {
        Uri context = Uri.create("https://hello.com/level1/level2/");

        Uri url = Uri.create(context, "../../other/content/img.png");

        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "hello.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/other/content/img.png");
        assertNull(url.getQuery());
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsAboveRoot() {
        Uri context = Uri.create("https://hello.com/level1/level2");

        Uri url = Uri.create(context, "../../other/content/img.png");

        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "hello.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/../other/content/img.png");
        assertNull(url.getQuery());
    }

    @Test
    public void testRelativeUriWithAbsoluteConsecutiveDots() {
        Uri context = Uri.create("https://hello.com/level1/level2/");

        Uri url = Uri.create(context, "/../../other/content/img.png");

        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "hello.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/../../other/content/img.png");
        assertNull(url.getQuery());
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromRoot() {
        Uri context = Uri.create("https://hello.com/");

        Uri url = Uri.create(context, "../../../other/content/img.png");

        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "hello.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/../../../other/content/img.png");
        assertNull(url.getQuery());
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromRootResource() {
        Uri context = Uri.create("https://hello.com/level1");

        Uri url = Uri.create(context, "../../../other/content/img.png");

        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "hello.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/../../../other/content/img.png");
        assertNull(url.getQuery());
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromSubrootResource() {
        Uri context = Uri.create("https://hello.com/level1/level2");

        Uri url = Uri.create(context, "../../../other/content/img.png");

        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "hello.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/../../other/content/img.png");
        assertNull(url.getQuery());
    }

    @Test
    public void testRelativeUriWithConsecutiveDotsFromLevel3Resource() {
        Uri context = Uri.create("https://hello.com/level1/level2/level3");

        Uri url = Uri.create(context, "../../../other/content/img.png");

        assertEquals(url.getScheme(), "https");
        assertEquals(url.getHost(), "hello.com");
        assertEquals(url.getPort(), -1);
        assertEquals(url.getPath(), "/../other/content/img.png");
        assertNull(url.getQuery());
    }
}
