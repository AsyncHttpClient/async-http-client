package org.sonatype.ahc.suite;

/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
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

import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.Response;
import org.sonatype.ahc.suite.util.AsyncSuiteConfiguration;
import org.testng.annotations.Test;

import static org.testng.AssertJUnit.assertEquals;

/**
 * @author Benjamin Hanzelmann
 */
public class HeadTest
        extends AsyncSuiteConfiguration {

    @Test(groups = "standalone")
    public void testSimple()
            throws Exception {
        BoundRequestBuilder rb = client().prepareHead(url("content", "something"));
        Response response = execute(rb);
        assertEquals(200, response.getStatusCode());
        assertEquals("0", response.getHeader("Content-Length"));
        assertEquals("", response.getResponseBody());
    }
}
