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
 *
 */
package ning.http;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 *
 */
public class IllegalRequestException extends RuntimeException {
    private static final long serialVersionUID = -2952073672138053678L;

    private final int responseCode;
    private final Collection<RequestError> errors = new ArrayList<RequestError>();

    public IllegalRequestException(int responseCode) {
        this(responseCode, (String) null);
    }

    public IllegalRequestException(int responseCode, String explanation) {
        super(explanation);
        this.responseCode = responseCode;
        errors.add(new RequestError(explanation));
    }

    public IllegalRequestException(int responseCode, String explanation, Throwable throwable) {
        super(explanation, throwable);
        this.responseCode = responseCode;
        errors.add(new RequestError(explanation));
    }

    public IllegalRequestException(int responseCode, Collection<RequestError> errors) {
        this.responseCode = responseCode;
        this.errors.addAll(errors);
    }

    public IllegalRequestException(int responseCode, RequestError error) {
        this.responseCode = responseCode;
        this.errors.add(error);
    }

    public int getResponseCode() {
        return responseCode;
    }

    public Collection<RequestError> getErrors() {
        return Collections.unmodifiableCollection(errors);
    }

    @Override
    public String toString() {
        return String.format("IllegalRequestException { errorCode => %d , explanation => %s }",
                responseCode,
                getMessage());
    }
}
