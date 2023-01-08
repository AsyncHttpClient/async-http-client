/*
 *    Copyright (c) 2014-2023 AsyncHttpClient Project. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.asynchttpclient.request.body.multipart;

import org.asynchttpclient.Param;

import java.nio.charset.Charset;
import java.util.List;

public interface Part {

    /**
     * Return the name of this part.
     *
     * @return The name.
     */
    String getName();

    /**
     * Returns the content type of this part.
     *
     * @return the content type, or {@code null} to exclude the content
     * type header
     */
    String getContentType();

    /**
     * Return the character encoding of this part.
     *
     * @return the character encoding, or {@code null} to exclude the
     * character encoding header
     */
    Charset getCharset();

    /**
     * Return the transfer encoding of this part.
     *
     * @return the transfer encoding, or {@code null} to exclude the
     * transfer encoding header
     */
    String getTransferEncoding();

    /**
     * Return the content ID of this part.
     *
     * @return the content ID, or {@code null} to exclude the content ID
     * header
     */
    String getContentId();

    /**
     * Gets the disposition-type to be used in Content-Disposition header
     *
     * @return the disposition-type
     */
    String getDispositionType();

    List<Param> getCustomHeaders();
}
