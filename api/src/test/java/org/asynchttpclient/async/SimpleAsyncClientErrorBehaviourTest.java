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
package org.asynchttpclient.async;

import static org.testng.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Future;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.asynchttpclient.Response;
import org.asynchttpclient.SimpleAsyncHttpClient;
import org.asynchttpclient.SimpleAsyncHttpClient.ErrorDocumentBehaviour;
import org.asynchttpclient.consumers.OutputStreamBodyConsumer;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.testng.annotations.Test;

/**
 * @author Benjamin Hanzelmann
 * 
 */
public abstract class SimpleAsyncClientErrorBehaviourTest extends AbstractBasicTest {
    
    public abstract String getProviderClass();

    @Test(groups = { "standalone", "default_provider" })
    public void testAccumulateErrorBody() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setUrl(getTargetUrl() + "/nonexistent").setErrorDocumentBehaviour(ErrorDocumentBehaviour.ACCUMULATE).build();
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(10);
            Future<Response> future = client.get(new OutputStreamBodyConsumer(o));

            System.out.println("waiting for response");
            Response response = future.get();
            assertEquals(response.getStatusCode(), 404);
            assertEquals(o.toString(), "");
            assertTrue(response.getResponseBody().startsWith("<html>"));
        } finally {
            client.close();
        }
    }

    @Test(groups = { "standalone", "default_provider" })
    public void testOmitErrorBody() throws Exception {
        SimpleAsyncHttpClient client = new SimpleAsyncHttpClient.Builder().setProviderClass(getProviderClass()).setUrl(getTargetUrl() + "/nonexistent").setErrorDocumentBehaviour(ErrorDocumentBehaviour.OMIT).build();
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(10);
            Future<Response> future = client.get(new OutputStreamBodyConsumer(o));

            System.out.println("waiting for response");
            Response response = future.get();
            assertEquals(response.getStatusCode(), 404);
            assertEquals(o.toString(), "");
            assertEquals(response.getResponseBody(), "");
        } finally {
            client.close();
        }
    }

    @Override
    public AbstractHandler configureHandler() throws Exception {
        return new AbstractHandler() {

            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                response.sendError(404);
                baseRequest.setHandled(true);
            }
        };
    }

}
