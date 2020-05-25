/*
 * Copyright SoftInstigate srl. All Rights Reserved.
 *
 *
 * The copyright to the computer program(s) herein is the property of
 * SoftInstigate srl, Italy. The program(s) may be used and/or copied only
 * with the written permission of SoftInstigate srl or in accordance with the
 * terms and conditions stipulated in the agreement/contract under which the
 * program(s) have been supplied. This copyright notice must not be removed.
 */
package com.softinstigate.lickeys;

import org.junit.Ignore;
import org.junit.Test;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
public class TestCommLicense {

    public TestCommLicense() {
    }

    @Test
    @Ignore // skip because requires manual acceptance of license
    public void testActivation() {
        System.setProperty(CommLicense.BASE_PATH_PROP_NAME, "src/test/resources/licKey");

        var activator = new CommLicense();

        activator.init();
    }
}
