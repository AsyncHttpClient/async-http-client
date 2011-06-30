/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.http.client;

/**
 * Interface that allows injecting signature calculator into
 * {@link RequestBuilder} so that signature calculation and inclusion can
 * be added as a pluggable component.
 *
 * @since 1.1
 */
public interface SignatureCalculator {
    /**
     * Method called when {@link RequestBuilder#build} method is called.
     * Should first calculate signature information and then modify request
     * (using passed {@link RequestBuilder}) to add signature (usually as
     * an HTTP header).
     *
     * @param requestBuilder builder that can be used to modify request, usually
     *                       by adding header that includes calculated signature. Be sure NOT to
     *                       call {@link RequestBuilder#build} since this will cause infinite recursion
     * @param request        Request that is being built; needed to access content to
     *                       be signed
     */
    public void calculateAndAddSignature(String url, Request request,
                                         RequestBuilderBase<?> requestBuilder);
}
