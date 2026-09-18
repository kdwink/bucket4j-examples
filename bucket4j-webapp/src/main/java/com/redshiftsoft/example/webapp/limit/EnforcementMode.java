package com.redshiftsoft.example.webapp.limit;

/**
 * How strictly a {@link RateLimitPolicy} is applied.
 */
public enum EnforcementMode {

    /**
     * Limits are not evaluated at all.
     */
    DISABLED,

    /**
     * Limits are evaluated and breaches recorded, but every request is still served.
     */
    RECORD,

    /**
     * Limits are evaluated and requests that exceed them are rejected.
     */
    BLOCK

}
