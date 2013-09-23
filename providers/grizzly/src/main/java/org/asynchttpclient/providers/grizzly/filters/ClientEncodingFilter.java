/*
 * Copyright (c) 2013 Sonatype, Inc. All rights reserved.
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

package org.asynchttpclient.providers.grizzly.filters;

import org.glassfish.grizzly.http.EncodingFilter;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpResponsePacket;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.Header;

/**
 * {@link EncodingFilter} to enable gzip encoding.
 *
 * @since 1.7
 * @author The Grizzly Team
 */
public final class ClientEncodingFilter implements EncodingFilter {


    // --------------------------------------------- Methods from EncodingFilter


    public boolean applyEncoding(HttpHeader httpPacket) {

       httpPacket.addHeader(Header.AcceptEncoding, "gzip");
       return false;

    }


    public boolean applyDecoding(HttpHeader httpPacket) {

        final HttpResponsePacket httpResponse = (HttpResponsePacket) httpPacket;
        final DataChunk bc = httpResponse.getHeaders().getValue(Header.ContentEncoding);
        return bc != null && bc.indexOf("gzip", 0) != -1;

    }


}
