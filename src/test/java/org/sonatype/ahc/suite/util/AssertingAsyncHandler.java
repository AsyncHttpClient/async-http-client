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
import com.ning.http.client.AsyncHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.Response;
import org.testng.Assert;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;

/**
 * @author Benjamin Hanzelmann
 */
public class AssertingAsyncHandler
        extends AsyncCompletionHandler<Response>
        implements AsyncHandler<Response> {

    private Queue<String> bodyParts = new LinkedList<String>();

    private Error assertionError;

    private Queue<Throwable> expectedThrowables;

    public void addBodyParts(String... parts) {
        for (String part : parts) {
            bodyParts.add(part);
        }
    }

    @Override
    public Response onCompleted(Response response)
            throws Exception {
        return response;
    }

    @Override
    public com.ning.http.client.AsyncHandler.STATE onBodyPartReceived(HttpResponseBodyPart content)
            throws Exception {
        com.ning.http.client.AsyncHandler.STATE ret = super.onBodyPartReceived(content);
        try {
            String head = bodyParts.poll();
            assertNotNull("no more bodyParts expected", head);
            String msg = "bodyPart did not match (expected: " + head + ")";
            byte[] actual = head.getBytes("UTF-8");
            Assert.assertEquals(content.getBodyPartBytes(), actual, msg);
            return ret;
        }
        catch (Error t) {
            this.assertionError = t;
            throw new Exception(t);
        }
    }

    @Override
    public void onThrowable(Throwable t) {
        try {
            super.onThrowable(t);
            Throwable expectedThrowable = expectedThrowables.poll();
            assertNotNull("Encountered more errors than expected: " + t.getClass() + "\n" + t.getMessage(),
                    expectedThrowable);
            assertEquals("expected different type of error", expectedThrowable.getClass(), t.getClass());
        }
        catch (Error assertionError) {
            this.assertionError = assertionError;
        }
    }

    public void setExpectedThrowables(Throwable... expectedThrowables) {
        this.expectedThrowables = new LinkedList<Throwable>(Arrays.asList(expectedThrowables));
    }

    public Error getAssertionError() {
        return assertionError;
    }

}