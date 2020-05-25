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

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
public class InvalidLicenseKeyException extends Exception {
    
    public InvalidLicenseKeyException() {
        super();
    }
    
    public InvalidLicenseKeyException(String message) {
        super(message);
    }
    
    public InvalidLicenseKeyException(String message, Throwable cause) {
        super(message, cause);
    }
    
}
