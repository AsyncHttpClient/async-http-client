package org.asynchttpclient.util;

import java.util.Properties;

import mockit.Deencapsulation;

import org.asynchttpclient.AsyncHttpClientConfig;
import org.asynchttpclient.AsyncImplHelperMock;
import org.asynchttpclient.AsyncHttpClientConfig.Builder;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MiscUtilTest {
	private final Integer MY_SPECIAL_INT_VALUE = 10;
	private final Integer MY_SPECIAL_SYSTEM_INT_VALUE = 100;
	private final String MY_SPECIAL_INT_PROPERTY = "my.special.int.property";
    private final String MY_SPECIAL_BOOLEAN_PROPERTY = "my.special.boolean.property";
    private final Integer MY_SPECIAL_INT_DEFAULT_VALUE = -100;

	
	@Test
    public void testGetIntegerValue() {
        // Setup a AsyncImplHelperMock that returns a mock
        // asynchttpclient.properties with a value
        // set for 'my.special.int.property' property
        Properties properties = new Properties();
        properties.setProperty(MY_SPECIAL_INT_PROPERTY, MY_SPECIAL_INT_VALUE.toString());
        AsyncImplHelperMock asyncImplHelperMock = new AsyncImplHelperMock(properties);

        
        // Assert that the getIntValue() method returns 10
        Assert.assertEquals(MiscUtil.getIntValue(MY_SPECIAL_INT_PROPERTY, -1), MY_SPECIAL_INT_VALUE);
        // Set a system property that overrides the value in the
        // asynchttpclient.properties
        System.setProperty(MY_SPECIAL_INT_PROPERTY, MY_SPECIAL_SYSTEM_INT_VALUE.toString());
        // Assert 100 is returned, i.e. system property takes precedence over
        // property in asynchttpclient.properties
        Assert.assertEquals(MiscUtil.getIntValue(MY_SPECIAL_INT_PROPERTY, -1),MY_SPECIAL_SYSTEM_INT_VALUE);
        // Clear the system property
        System.clearProperty(MY_SPECIAL_INT_PROPERTY);
        // Assert that the value set in asynchttpclient.properties is returned
        Assert.assertEquals(MiscUtil.getIntValue(MY_SPECIAL_INT_PROPERTY, -1), MY_SPECIAL_INT_VALUE);
        // Set a corrupt system property
        System.setProperty(MY_SPECIAL_INT_PROPERTY, "corrupt property");
        // Assert that the value set in asynchttpclient.properties is returned
        // even though corrupt system property is set.
        Assert.assertEquals(MiscUtil.getIntValue(MY_SPECIAL_INT_PROPERTY, -1), MY_SPECIAL_INT_VALUE);
        System.clearProperty(MY_SPECIAL_INT_PROPERTY);
    }
	
	 @Test
	    public void testGetBooleanValue() {
	        // Setup a AsyncImplHelperMock that returns a mock
	        // asynchttpclient.properties with a value
	        // set for 'my.special.int.property' property
	        Properties properties = new Properties();
	        properties.setProperty(MY_SPECIAL_BOOLEAN_PROPERTY, "true");
	        AsyncImplHelperMock asyncImplHelperMock = new AsyncImplHelperMock(properties);

	        
	        // Assert that the getBooleanValue() method returns TRUE
	        Assert.assertTrue(MiscUtil.getBooleanValue(MY_SPECIAL_BOOLEAN_PROPERTY, false));
	        // Set a system property that overrides the value in the
	        // asynchttpclient.properties
	        System.setProperty(MY_SPECIAL_BOOLEAN_PROPERTY, "false");
	        // Assert false is returned, i.e. system property takes precedence over
	        // property in asynchttpclient.properties
	        Assert.assertFalse(MiscUtil.getBooleanValue(MY_SPECIAL_BOOLEAN_PROPERTY, true));
	        // Clear the system property
	        System.clearProperty(MY_SPECIAL_BOOLEAN_PROPERTY);
	        // Assert that the value set in asynchttpclient.properties is returned
	        Assert.assertTrue(MiscUtil.getBooleanValue(MY_SPECIAL_BOOLEAN_PROPERTY, false));
	        // Set a corrupt system property
	        System.setProperty(MY_SPECIAL_BOOLEAN_PROPERTY, "corrupt property");
	        // Assert that the value set in asynchttpclient.properties is returned
	        // even though corrupt system property is set.
	        Assert.assertTrue(MiscUtil.getBooleanValue(MY_SPECIAL_BOOLEAN_PROPERTY, false));
	        System.clearProperty(MY_SPECIAL_BOOLEAN_PROPERTY);
	    }
	 
	 @Test
	    public void testGetDefaultIntegerValue() {
	        
	        // Assert that the getIntValue() method returns the default value if
	        // Properties is not present
	        Assert.assertEquals(
	        		MiscUtil.getIntValue(MY_SPECIAL_INT_PROPERTY, MY_SPECIAL_INT_DEFAULT_VALUE),
	                MY_SPECIAL_INT_DEFAULT_VALUE);
	        // Setup up a mock of a asynchttpclient.properties that initially is empty
	        Properties properties = new Properties();        
	        AsyncImplHelperMock asyncImplHelperMock = new AsyncImplHelperMock(properties);

	        
	        // Assert that the getIntValue() method returns the default value if there is no
	        // property set in the asynchttpclient.properties
	        Assert.assertEquals(
	        		MiscUtil.getIntValue(MY_SPECIAL_INT_PROPERTY, MY_SPECIAL_INT_DEFAULT_VALUE),
	                MY_SPECIAL_INT_DEFAULT_VALUE);
	        //Now set a corrupt value in the asynchttpclient.properties
	        properties.setProperty(MY_SPECIAL_INT_PROPERTY, "corrupt property");
	        // Assert that the getIntValue() method returns the default value if there is a corrupt
	        // property set in the asynchttpclient.properties
	        Assert.assertEquals(
	        		MiscUtil.getIntValue(MY_SPECIAL_INT_PROPERTY, MY_SPECIAL_INT_DEFAULT_VALUE),
	                MY_SPECIAL_INT_DEFAULT_VALUE);
	        // Set a corrupt system property
	        System.setProperty(MY_SPECIAL_INT_PROPERTY, "corrupt property");
	        // Assert that even though values set in asynchttpclient.properties and system property is corrupt the default value is returned
	        Assert.assertEquals(MiscUtil.getIntValue(MY_SPECIAL_INT_PROPERTY, MY_SPECIAL_INT_DEFAULT_VALUE),
	                MY_SPECIAL_INT_DEFAULT_VALUE);
	        System.clearProperty(MY_SPECIAL_INT_PROPERTY);
	    }

	   
	    
	    @Test
	    public void testGetDefaultBooleanValue() {	        
	        // Assert that the getBooleanValue() method returns the default value if
	        // asynchttpclient.properties is not present
	        Assert.assertTrue(MiscUtil.getBooleanValue(MY_SPECIAL_BOOLEAN_PROPERTY, true));
	        // Setup up a mock of a asynchttpclient.properties that initially is empty
	        Properties properties = new Properties();        
	        AsyncImplHelperMock asyncImplHelperMock = new AsyncImplHelperMock(properties);

	        
	        // Assert that the getBooleanValue() method returns the default value if there is no
	        // property set in the asynchttpclient.properties
	        Assert.assertTrue(!MiscUtil.getBooleanValue(MY_SPECIAL_BOOLEAN_PROPERTY, false));
	        //Now set a corrupt value in the asynchttpclient.properties
	        properties.setProperty(MY_SPECIAL_BOOLEAN_PROPERTY, "corrupt property");
	        // Assert that the getBooleanValue() method returns the default value if there is a corrupt
	        // property set in the asynchttpclient.properties
	        Assert.assertTrue(MiscUtil.getBooleanValue(MY_SPECIAL_BOOLEAN_PROPERTY, true));
	        // Set a corrupt system property
	        System.setProperty(MY_SPECIAL_BOOLEAN_PROPERTY, "corrupt property");
	        // Assert that even though values set in asynchttpclient.properties and system property is corrupt the default value is returned
	        Assert.assertTrue(!MiscUtil.getBooleanValue(MY_SPECIAL_BOOLEAN_PROPERTY, false));
	        System.clearProperty(MY_SPECIAL_INT_PROPERTY);
	    }
}
