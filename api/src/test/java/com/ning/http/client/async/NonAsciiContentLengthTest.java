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
package com.ning.http.client.async;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.Response;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.testng.Assert.assertEquals;

public abstract class NonAsciiContentLengthTest extends AbstractBasicTest {


	public void setUpServer() throws Exception {
		server = new Server();
		port1 = findFreePort();
		Connector listener = new SelectChannelConnector();

		listener.setHost("127.0.0.1");
		listener.setPort(port1);
		server.addConnector(listener);
		server.setHandler(new AbstractHandler() {

			public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
			    throws IOException, ServletException {
				int MAX_BODY_SIZE = 1024; //Can only handle bodies of up to 1024 bytes.
				byte[] b = new byte[MAX_BODY_SIZE];
				int offset = 0;
				int numBytesRead;
				ServletInputStream is = request.getInputStream();
				try {
					while ((numBytesRead = is.read(b, offset, MAX_BODY_SIZE - offset)) != -1) {
						offset += numBytesRead;
					}
				} finally {
					is.close();
				}
				assertEquals(request.getContentLength(), offset);
				response.setStatus(200);
				response.setCharacterEncoding(request.getCharacterEncoding());
				response.setContentLength(request.getContentLength());
				ServletOutputStream os = response.getOutputStream();
				try {
					os.write(b, 0, offset);
				} finally {
					os.close();
				}
			}
		});
		server.start();
	}

	@Test(groups = { "standalone", "default_provider" })
	public void testNonAsciiContentLength() throws Exception {
		setUpServer();
		execute("test");
		execute("\u4E00"); // Unicode CJK ideograph for one
	}

	protected void execute(String body) throws IOException, InterruptedException, ExecutionException {
		AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().build());
		BoundRequestBuilder r = client.preparePost(getTargetUrl()).setBody(body).setBodyEncoding("UTF-8");
		Future<Response> f = r.execute();
		Response resp = f.get();
		assertEquals(resp.getStatusCode(), 200);
		assertEquals(body, resp.getResponseBody("UTF-8"));
		client.close();
	}

}
