package pitest.test;

import org.junit.Test;

import pitest.test.Library;
import org.json.JSONObject;

import static org.junit.Assert.*;

public class LibraryTest {
    @Test
    public void testSomeLibraryMethod() {
        Library classUnderTest = new Library();
        assertTrue("someLibraryMethod should return 'true'", classUnderTest.someLibraryMethod());
    }

    @Test
    public void testJSON() throws Exception {
        JSONObject jsonObject = new JSONObject("{\"id\": 123}");
        assertEquals("123", jsonObject.optString("id"));
    }
}
