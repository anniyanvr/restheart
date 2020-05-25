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

import com.auth0.jwt.interfaces.Claim;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
public class LicenseKeyClaims {
    private static final Logger LOGGER
            = LoggerFactory.getLogger("org.restheart.CommLicense");

    private String jti = null;
    private String licensee = null;
    private String licensor = null;
    private String type = null;
    private String subscriptionPeriod = null;
    private String iss = null;
    private Long iat = null;
    private Long exp = null;
    private Integer maxMachines = null;
    private Boolean floating = null;
    private Boolean concurrent = null;
    private String additionalConditions = null;
    private String refJti = null;
    private String licenseHash = null;

    public LicenseKeyClaims() {
    }

    public LicenseKeyClaims(Map<String, Claim> claims) {
        this();

        jti = claims.containsKey("jti") ? claims.get("jti").asString() : null;
        licensee = claims.containsKey("licensee") ? claims.get("licensee").asString() : null;
        licensor = claims.containsKey("licensor") ? claims.get("licensor").asString() : null;
        type = claims.containsKey("type") ? claims.get("type").asString() : null;
        subscriptionPeriod = claims.containsKey("subscriptionPeriod") ? claims.get("subscriptionPeriod").asString() : null;
        iss = claims.containsKey("iss") ? claims.get("iss").asString() : null;
        iat = claims.containsKey("iat") ? claims.get("iat").asLong() : null;
        exp = claims.containsKey("exp") ? claims.get("exp").asLong() : null;
        maxMachines = claims.containsKey("maxMachines") ? claims.get("maxMachines").asInt() : null;
        floating = claims.containsKey("floating") ? claims.get("floating").asBoolean() : null;
        concurrent = claims.containsKey("concurrent") ? claims.get("concurrent").asBoolean() : null;
        additionalConditions = claims.containsKey("additionalConditions") ? claims.get("additionalConditions").asString() : null;
        refJti = claims.containsKey("refJti") ? claims.get("refJti").asString() : "none";
        licenseHash = claims.containsKey("licenseHash") ? claims.get("licenseHash").asString() : null;
    }

    @Override
    public String toString() {
        return "Key Id                : " + jti + "\n"
                + "Licensee              : " + licensee + "\n"
                + "Licensor              : " + licensor + "\n"
                + "Type                  : " + type + "\n"
                + "Additional Conditions : " + additionalConditions + "\n"
                + "Subscription Period   : " + subscriptionPeriod + "\n"
                + "Issuer                : " + iss + "\n"
                + "Issued at             : " + (iat == null ? iat : new Date(iat * 1000).toString()) + "\n"
                + "Expires               : " + (exp == null ? "Perpetual" : new Date(exp * 1000).toString()) + "\n"
                + "Maximum Machines      : " + maxMachines + "\n"
                + "Floating              : " + floating + "\n"
                + "Concurrent            : " + concurrent + "\n"
                + "Reference Key Id      : " + refJti + "\n"
                + "License File Hash     : " + licenseHash + "\n";
    }

    /**
     * @return the jwtid
     */
    public String getJti() {
        return jti;
    }

    /**
     * @param jwtid the jwtid to set
     */
    public void setJti(String jwtid) {
        this.jti = jwtid;
    }

    /**
     * @return the licensee
     */
    public String getLicensee() {
        return licensee;
    }

    /**
     * @param licensee the licensee to set
     */
    public void setLicensee(String licensee) {
        this.licensee = licensee;
    }

    /**
     * @return the licensor
     */
    public String getLicensor() {
        return licensor;
    }

    /**
     * @param licensor the licensor to set
     */
    public void setLicensor(String licensor) {
        this.licensor = licensor;
    }

    /**
     * @return the type
     */
    public String getType() {
        return type;
    }

    /**
     * @param type the type to set
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * @return the subscriptionPeriod
     */
    public String getSubscriptionPeriod() {
        return subscriptionPeriod;
    }

    private static DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /**
     * @return the subscriptionPeriod
     */
    public Instant getSubscriptionEnd() {
        if (subscriptionPeriod == null || subscriptionPeriod.length() != 29) {
            LOGGER.warn("The license key does not contain the "
                    + "'subscriptionPeriod' claim. Please contact the support");
            return Instant.ofEpochSecond(iat);
        } else {
            try {
                return LocalDate
                        .parse(subscriptionPeriod.substring(19), DATE_FORMAT)
                        .atStartOfDay()
                        .plusDays(1)
                        .minusSeconds(1)
                        .toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException dpe) {
                LOGGER.warn("The license key 'subscriptionPeriod' claim is "
                        + "invalid. Please contact the support");
                return Instant.ofEpochSecond(iat);
            }
        }
    }

    /**
     * @param subscriptionPeriod the subscriptionPeriod to set
     */
    public void setSubscriptionPeriod(String subscriptionPeriod) {
        this.subscriptionPeriod = subscriptionPeriod;
    }

    /**
     * @return the iss
     */
    public String getIss() {
        return iss;
    }

    /**
     * @param iss the iss to set
     */
    public void setIss(String iss) {
        this.iss = iss;
    }

    /**
     * @return the iat
     */
    public Date getIat() {
        return iat == null ? null : new Date(iat * 1000);
    }

    /**
     * @param iat the iat to set
     */
    public void setIat(Long iat) {
        this.iat = iat;
    }

    /**
     * @return the exp
     */
    public Date getExp() {
        return exp == null ? null : new Date(exp * 1000);
    }

    /**
     * @param exp the exp to set
     */
    public void setExp(Long exp) {
        this.exp = exp;
    }

    /**
     * @return the maxMachines
     */
    public Integer getMaxMachines() {
        return maxMachines;
    }

    /**
     * @param maxMachines the maxMachines to set
     */
    public void setMaxMachines(Integer maxMachines) {
        this.maxMachines = maxMachines;
    }

    /**
     * @return the floating
     */
    public Boolean getFloating() {
        return floating;
    }

    /**
     * @param floating the floating to set
     */
    public void setFloating(Boolean floating) {
        this.floating = floating;
    }

    /**
     * @return the concurrent
     */
    public Boolean getConcurrent() {
        return concurrent;
    }

    /**
     * @param concurrent the concurrent to set
     */
    public void setConcurrent(Boolean concurrent) {
        this.concurrent = concurrent;
    }

    /**
     * @return the additionalConditions
     */
    public String getAdditionalConditions() {
        return additionalConditions;
    }

    /**
     * @param additionalConditions the additionalConditions to set
     */
    public void setAdditionalConditions(String additionalConditions) {
        this.additionalConditions = additionalConditions;
    }

    /**
     * @return the refJti
     */
    public String getRefJti() {
        return refJti;
    }

    /**
     * @param refJti the refJti to set
     */
    public void setRefJti(String refJti) {
        this.refJti = refJti;
    }

    /**
     * @return the licenseHash
     */
    public String getLicenseHash() {
        return licenseHash;
    }

    /**
     * @param licenseHash the licenseHash to set
     */
    public void setLicenseHash(String licenseHash) {
        this.licenseHash = licenseHash;
    }
}
