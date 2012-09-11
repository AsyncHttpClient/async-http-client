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
package com.ning.http.util;

import com.ning.http.client.Cookie;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TestAsyncHttpProviderUtils
{
    @Test(groups="fast")
    public void testCookieParsing()
    {
        String cookieValue = "ID=a3be7f468f2a528c:FF=0:TM=1397369269:LM=134759269:S=XZQK3o8HJ1mytzgz";
        String testCookie = "PREF=" + cookieValue + "; expires=Thu, 11-Sep-2013 13:14:29 GMT; path=/; domain=.google.co.uk";
        Cookie cookie = AsyncHttpProviderUtils.parseCookie(testCookie);
        
        Assert.assertEquals(cookie.getValue(), cookieValue);
    }
}
