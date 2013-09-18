/*
 * Copyright (c) 2010-2012 Sonatype, Inc. All rights reserved.
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
package org.asynchttpclient.util;

import static org.testng.Assert.assertEquals;

import java.net.URI;

import org.testng.annotations.Test;

public class AsyncHttpProviderUtilsTest {

    @Test(groups = "fast")
    public void getRedirectUriShouldHandleProperlyEncodedLocation() {

        String url = "http://www.ebay.de/sch/sis.html;jsessionid=92D73F80262E3EBED7E115ED01035DDA?_nkw=FSC%20Lifebook%20E8310%20Core2Duo%20T8100%202%201GHz%204GB%20DVD%20RW&_itemId=150731406505";
        URI uri = AsyncHttpProviderUtils.getRedirectUri(
                URI.create("http://www.ebay.de"), url);
        assertEquals( uri.toString(), "http://www.ebay.de/sch/sis.html;jsessionid=92D73F80262E3EBED7E115ED01035DDA?_nkw=FSC%20Lifebook%20E8310%20Core2Duo%20T8100%202%201GHz%204GB%20DVD%20RW&_itemId=150731406505");
    }

    @Test(groups = "fast")
    public void getRedirectUriShouldHandleRawQueryParamsLocation() {

        String url = "http://www.ebay.de/sch/sis.html;jsessionid=92D73F80262E3EBED7E115ED01035DDA?_nkw=FSC Lifebook E8310 Core2Duo T8100 2 1GHz 4GB DVD RW&_itemId=150731406505";
        URI uri = AsyncHttpProviderUtils.getRedirectUri(URI.create("http://www.ebay.de"), url);
        assertEquals(uri.toString(), "http://www.ebay.de/sch/sis.html;jsessionid=92D73F80262E3EBED7E115ED01035DDA?_nkw=FSC%20Lifebook%20E8310%20Core2Duo%20T8100%202%201GHz%204GB%20DVD%20RW&_itemId=150731406505");
    }
    
    @Test(groups = "fast")
    public void getRedirectUriShouldHandleRelativeLocation() {

        String url = "/sch/sis.html;jsessionid=92D73F80262E3EBED7E115ED01035DDA?_nkw=FSC Lifebook E8310 Core2Duo T8100 2 1GHz 4GB DVD RW&_itemId=150731406505";
        URI uri = AsyncHttpProviderUtils.getRedirectUri(URI.create("http://www.ebay.de"), url);
        assertEquals(uri.toString(), "http://www.ebay.de/sch/sis.html;jsessionid=92D73F80262E3EBED7E115ED01035DDA?_nkw=FSC%20Lifebook%20E8310%20Core2Duo%20T8100%202%201GHz%204GB%20DVD%20RW&_itemId=150731406505");
    }
}
