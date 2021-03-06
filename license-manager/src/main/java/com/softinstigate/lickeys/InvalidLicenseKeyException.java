/*-
 * ========================LICENSE_START=================================
 * restheart-license-manager
 * %%
 * Copyright (C) 2018 - 2020 SoftInstigate
 * %%
 *
 * The copyright to the computer program(s) herein is the property of
 * SoftInstigate srl, Italy. The program(s) may be used in accordance with the 
 * terms and conditions stipulated in the license under which the
 * program(s) have been supplied and can be modified only with the written 
 * permission of SoftInstigate srl. This copyright notice must not be removed.
 *
 * =========================LICENSE_END==================================
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
