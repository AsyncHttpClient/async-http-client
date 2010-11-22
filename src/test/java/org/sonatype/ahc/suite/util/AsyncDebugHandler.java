package org.sonatype.ahc.suite.util;

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

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.Response;

import java.nio.ByteBuffer;

/**
 * @author Benjamin Hanzelmann
 */
public class AsyncDebugHandler
        extends AsyncCompletionHandler<Response> {

    private long start;

    public AsyncDebugHandler() {
        super();
        start = System.currentTimeMillis();
    }

    @Override
    public Response onCompleted(Response response)
            throws Exception {
        System.err.println("Completed after " + (System.currentTimeMillis() - start) + "ms");
        return response;
    }

    @Override
    public com.ning.http.client.AsyncHandler.STATE onBodyPartReceived(HttpResponseBodyPart content)
            throws Exception {
        ByteBuffer buf = content.getBodyByteBuffer();
        String ret = "";
        while (buf.remaining() > 1) {
            ret += buf.getChar();
        }
        System.err.println("Body Part received after " + (System.currentTimeMillis() - start) + "ms:\n" + buf);
        return super.onBodyPartReceived(content);
    }

    @Override
    public void onThrowable(Throwable t) {
        System.err.println("throwable received after " + (System.currentTimeMillis() - start) + "ms:\n"
                + t.getMessage());
        t.printStackTrace();
        super.onThrowable(t);
    }
}