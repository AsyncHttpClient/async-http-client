package org.asynchttpclient.test;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class TestUtilsTest {

    @Test(groups = "fast")
    public void testLocalhostIpAddress() {
        /*
         * Test formatted strings equality
         */
        
        final int port1 = 1122;
        final String protocol = "ws";
        final String query = "testing";
        
        assertEquals(String.format("http://127.0.0.1:%d/foo/test", port1), String.format("http://%s:%d/foo/test", 
                TestUtils.getUnitTestIpAddress(), port1));
        
        assertEquals("http://127.0.0.1:" + port1, String.format("http://%s:%d", TestUtils.getUnitTestIpAddress(), port1));
        
        assertEquals(String.format("%s://127.0.0.1:%d/", protocol, port1), String.format("%s://%s:%d/", protocol,
                TestUtils.getUnitTestIpAddress(), port1));
        
        assertEquals(String.format("http://127.0.0.1:%d/foo/test/colon?q=%s", port1, query), 
                String.format("http://%s:%d/foo/test/colon?q=%s", TestUtils.getUnitTestIpAddress(), port1, query));
        
        assertEquals("http://127.0.0.1:" + port1 + "/timeout/", String.format("http://%s:%d/timeout/", TestUtils.getUnitTestIpAddress(), port1));        

    }

}
