package pitest.sample.dynamicfeature.dynamic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DynamicFeatureUtilTest {

    @Test
    public void testGetFeatureName() {
        DynamicFeatureUtil util = new DynamicFeatureUtil();
        assertEquals("dynamic_feature", util.getFeatureName());
    }

    @Test
    public void testIsEnabled() {
        DynamicFeatureUtil util = new DynamicFeatureUtil();
        assertEquals(true, util.isEnabled());
    }
}

