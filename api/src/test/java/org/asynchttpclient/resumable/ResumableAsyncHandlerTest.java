package org.asynchttpclient.resumable;

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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import org.asynchttpclient.Request;
import org.asynchttpclient.RequestBuilder;
import org.testng.annotations.Test;

/**
 * @author Benjamin Hanzelmann
 */
public class ResumableAsyncHandlerTest {
    @Test
    public void testAdjustRange() {
        MapResumableProcessor proc = new MapResumableProcessor();

        ResumableAsyncHandler h = new ResumableAsyncHandler(proc);
        Request request = new RequestBuilder("GET").setUrl("http://test/url").build();
        Request newRequest = h.adjustRequestRange(request);
        assertEquals(newRequest.getURI(), request.getURI());
        String rangeHeader = newRequest.getHeaders().getFirstValue("Range");
        assertNull(rangeHeader);

        proc.put("http://test/url", 5000);
        newRequest = h.adjustRequestRange(request);
        assertEquals(newRequest.getURI(), request.getURI());
        rangeHeader = newRequest.getHeaders().getFirstValue("Range");
        assertEquals(rangeHeader, "bytes=5000-");
    }
}
