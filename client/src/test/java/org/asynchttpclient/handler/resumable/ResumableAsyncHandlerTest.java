package org.asynchttpclient.handler.resumable;

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

import static org.asynchttpclient.Dsl.get;
import static org.testng.Assert.*;
import io.netty.handler.codec.http.HttpHeaders;

import org.asynchttpclient.Request;
import org.testng.annotations.Test;

/**
 * @author Benjamin Hanzelmann
 */
public class ResumableAsyncHandlerTest {

    @Test(groups = "standalone")
    public void testAdjustRange() {
        MapResumableProcessor proc = new MapResumableProcessor();

        ResumableAsyncHandler h = new ResumableAsyncHandler(proc);
        Request request = get("http://test/url").build();
        Request newRequest = h.adjustRequestRange(request);
        assertEquals(newRequest.getUri(), request.getUri());
        String rangeHeader = newRequest.getHeaders().get(HttpHeaders.Names.RANGE);
        assertNull(rangeHeader);

        proc.put("http://test/url", 5000);
        newRequest = h.adjustRequestRange(request);
        assertEquals(newRequest.getUri(), request.getUri());
        rangeHeader = newRequest.getHeaders().get(HttpHeaders.Names.RANGE);
        assertEquals(rangeHeader, "bytes=5000-");
    }
}
