package com.redshiftsoft.example.webapp.limit;

import java.util.Objects;

/**
 * A request matcher paired with the rate limits to apply. Build one with {@link #builder()}:
 *
 * <pre>
 *   Endpoint.builder()
 *           .method("POST")
 *           .path("/login")
 *           .policy(RateLimitPolicies.LOGIN_POLICY)
 *           .build();
 * </pre>
 *
 * @param method HTTP method to match (e.g. "GET", "POST"), or {@link #ANY} to match any method
 * @param path   request path to match
 * @param prefix true to match any path starting with {@code path}, false to match {@code path} exactly
 * @param policy rate limits applied to requests matching this endpoint
 * @param mode   overrides the policy's own mode for this endpoint, or {@code null} to use the policy's mode.
 *               Handy for turning a shared policy off — or down to {@link EnforcementMode#RECORD} — on one
 *               endpoint only
 */
public record Endpoint(String method,
                       String path,
                       boolean prefix,
                       RateLimitPolicy policy,
                       EnforcementMode mode) {

    /** Method value that matches any HTTP method. */
    public static final String ANY = "ANY";

    public Endpoint {
        Objects.requireNonNull(method, "method is required; use Endpoint.ANY to match any method");
        Objects.requireNonNull(path, "path is required");
        Objects.requireNonNull(policy, "policy is required");
        method = method.toUpperCase();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The mode actually applied: this endpoint's override when set, otherwise the policy's mode. */
    public EnforcementMode effectiveMode() {
        return mode != null ? mode : policy.mode();
    }

    public boolean matches(String requestMethod, String requestPath) {
        return matchesPath(requestPath) && (ANY.equals(method) || method.equalsIgnoreCase(requestMethod));
    }

    private boolean matchesPath(String requestPath) {
        return prefix ? requestPath.startsWith(path) : requestPath.equals(path);
    }

    public String key() {
        return method + " " + path + (prefix ? "*" : "");
    }

    public static final class Builder {

        private String method = ANY;
        private String path;
        private boolean prefix;
        private RateLimitPolicy policy;
        private EnforcementMode mode;

        private Builder() {
        }

        /** Defaults to {@link #ANY}. */
        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /** Defaults to false, an exact path match. */
        public Builder prefix(boolean prefix) {
            this.prefix = prefix;
            return this;
        }

        public Builder policy(RateLimitPolicy policy) {
            this.policy = policy;
            return this;
        }

        /** Leave unset to use the policy's own mode. */
        public Builder mode(EnforcementMode mode) {
            this.mode = mode;
            return this;
        }

        public Endpoint build() {
            return new Endpoint(method, path, prefix, policy, mode);
        }

    }

}
