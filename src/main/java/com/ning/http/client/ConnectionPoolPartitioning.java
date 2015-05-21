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
package com.ning.http.client;

import com.ning.http.client.uri.Uri;
import com.ning.http.util.AsyncHttpProviderUtils;

public interface ConnectionPoolPartitioning {

	Object getPartitionKey(Uri uri, ProxyServer proxyServer);
	
	public enum PerHostConnectionPoolPartitioning implements ConnectionPoolPartitioning {

	    INSTANCE;
	    
	    public String getPartitionKey(Uri uri, ProxyServer proxyServer) {
	        String serverPart = AsyncHttpProviderUtils.getBaseUrl(uri);
	        return proxyServer != null ? proxyServer.getUrl() + serverPart : serverPart;
	    }
	}

}
