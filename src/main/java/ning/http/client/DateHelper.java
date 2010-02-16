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
package ning.http.client;

import org.apache.commons.lang.StringUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DateHelper {
    private final ThreadLocal<SimpleDateFormat> format = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("EE, dd MMM yyyy HH:mm:ss zz", Locale.US);
        }
    };

    /**
     * Extracts a date from the given value which is e.g. a value of the Is-Modified-Since or Last-Modified headers.
     *
     * @param headerValue The header value
     * @return The date or <code>null</code> if no date could be parsed
     */
    public Date extractHeaderDate(String headerValue) {
        if (!StringUtils.isEmpty(headerValue)) {
            try {
                return format.get().parse(headerValue);
            }
            catch (ParseException e) {
                // ignored
            }
        }
        return null;
    }

    /**
     * Encodes the given date into a string value suitable for a request/response headers.
     *
     * @param date The date
     * @return The string value or <code>null</code> if no date was given
     */
    public String encodeHeaderDate(Date date) {
        if (date == null) {
            return null;
        } else {
            return format.get().format(date);
        }
    }

    /**
     * Returns the given date truncated to the precision relevant for headers which is seconds.
     *
     * @param date The date
     * @return The date truncated to seconds precision
     */
    public static Date truncateToHeaderPrecision(Date date) {
        return new Date(date.getTime() - (date.getTime() % 1000));
    }

    /**
     * Determines whether the given <code>lastModified</code> date represent a resource that was modified
     * since the last time the client saw it (as specified by <code>ifModifiedSince</code>).
     *
     * @param lastModified    The last modified date of the resource
     * @param ifModifiedSince The last time the client has seen the resource; can be <code>null</code>
     * @return <code>true</code> if the resource has been modified in the mean time
     */
    public static boolean isModifiedSince(Date lastModified, Date ifModifiedSince) {
        return (ifModifiedSince == null) || lastModified.after(ifModifiedSince);
    }
}
