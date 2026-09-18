package com.redshiftsoft.example.webapp.limit;

import com.redshiftsoft.example.webapp.servlets.LoginServlet;
import com.redshiftsoft.example.webapp.model.User;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class ThrottlingFilter implements jakarta.servlet.Filter {

    private static final Logger LOG = LoggerFactory.getLogger(ThrottlingFilter.class);

    private final List<Endpoint> endpoints;
    private final BucketRegistry bucketRegistry;
    /** True when this filter built the registry itself, and so is the one to close it. */
    private final boolean ownsRegistry;

    public ThrottlingFilter() {
        this(List.of(Endpoint.builder()
                .path("/")
                .prefix(true)
                .policy(RateLimitPolicies.DEFAULT_POLICY)
                .build()));
    }

    /**
     * @param endpoints candidate endpoints, in priority order: the <em>first</em> endpoint matching the
     *                  request method and path wins, so order matters. List more specific endpoints before
     *                  more general ones — e.g. an exact {@code /api/users} endpoint before a prefix
     *                  {@code /api} endpoint, and a {@code POST} endpoint before an {@link Endpoint#ANY}
     *                  endpoint on the same path — otherwise the general endpoint shadows the specific one.
     *                  End the list with a catch-all — path {@code "/"} with {@code prefix(true)} — to act as
     *                  the default; requests matching no endpoint are not throttled at all.
     */
    public ThrottlingFilter(List<Endpoint> endpoints) {
        this(endpoints, ValkeyBucketRegistry.connectDefault(), true);
    }

    /**
     * @param bucketRegistry where buckets live — a {@link ValkeyBucketRegistry} on the cluster this
     *                       application should share its limits with. The caller keeps ownership: the registry
     *                       outlives this filter and is not closed by {@link #destroy()}.
     */
    public ThrottlingFilter(List<Endpoint> endpoints, BucketRegistry bucketRegistry) {
        this(endpoints, bucketRegistry, false);
    }

    private ThrottlingFilter(List<Endpoint> endpoints, BucketRegistry bucketRegistry, boolean ownsRegistry) {
        this.endpoints = List.copyOf(endpoints);
        this.bucketRegistry = Objects.requireNonNull(bucketRegistry, "bucketRegistry is required");
        this.ownsRegistry = ownsRegistry;
    }

    /**
     * Closes the registry, but only the one this filter opened for itself — a registry handed to the
     * constructor belongs to the caller. A registry holds a cluster client, with its own connections and
     * threads, so leaving it open leaks them for the life of the JVM.
     */
    @Override
    public void destroy() {
        if (ownsRegistry && bucketRegistry instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                // Nothing useful left to do while shutting down, and destroy() must not throw.
                LOG.warn("Failed to close bucket registry", e);
            }
        }
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
        HttpSession session = httpRequest.getSession(true);
        String path = httpRequest.getServletPath();
        String method = httpRequest.getMethod();
        Endpoint endpoint = resolveEndpoint(method, path);

        // Anonymous requests, requests matching no endpoint, and endpoints enforced as DISABLED are passed
        // straight through without touching any bucket.
        User user = (User) session.getAttribute(LoginServlet.USER_SESSION_KEY);
        if (endpoint == null || user == null) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        EnforcementMode mode = endpoint.effectiveMode();
        if (mode == EnforcementMode.DISABLED) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        String endpointKey = endpoint.key();
        RateLimitPolicy policy = endpoint.policy();
        // The per-organization and concurrency buckets are both keyed by organization, so the scope has to be
        // part of the key — all three share one registry.
        Bucket userBucket = bucketRegistry.bucket("user|" + endpointKey + "|" + user.username(), policy.userBucket());
        Bucket orgBucket = bucketRegistry.bucket("org|" + endpointKey + "|" + user.organizationId(), policy.orgBucket());
        Bucket concurrentBucket = policy.hasConcurrencyLimit()
                ? bucketRegistry.bucket("concurrent|" + endpointKey + "|" + user.organizationId(), policy.concurrentBucket())
                : null;

        // In RECORD mode limits are still evaluated and tokens still consumed — only the rejection is skipped.
        boolean block = mode == EnforcementMode.BLOCK;

        if (!orgBucket.tryConsume(1)) {
            String message = "Too many requests for organization " + user.organizationId();
            if (block) {
                tooManyRequests(servletResponse, message);
                return;
            }
            record(endpointKey, message);
        }
        if (!userBucket.tryConsume(1)) {
            String message = "Too many requests for user " + user.username();
            if (block) {
                tooManyRequests(servletResponse, message);
                return;
            }
            record(endpointKey, message);
        }

        boolean permitAcquired = false;
        if (concurrentBucket != null) {
            permitAcquired = concurrentBucket.tryConsume(1);
            if (!permitAcquired) {
                String message = "Too many concurrent requests for organization " + user.organizationId();
                if (block) {
                    tooManyRequests(servletResponse, message);
                    return;
                }
                record(endpointKey, message);
            }
        }

        try {
            filterChain.doFilter(servletRequest, servletResponse);
        } finally {
            // A concurrency permit is held only for the duration of the request, so hand it back once the
            // request is done — unlike the token buckets, which refill on a schedule. Only refund a permit we
            // actually took: in RECORD mode the request proceeds even when none was available.
            if (permitAcquired) {
                concurrentBucket.addTokens(1);
            }
        }
    }

    /** Notes a breach that {@link EnforcementMode#RECORD} let through. */
    private void record(String endpointKey, String message) {
        LOG.warn("Rate limit exceeded on endpoint [{}] but request allowed (mode {}): {}",
                endpointKey, EnforcementMode.RECORD, message);
    }

    /**
     * Returns the first matching endpoint — {@code endpoints} is evaluated in order — or {@code null} if
     * none match, which happens only when the list has no catch-all entry.
     */
    private Endpoint resolveEndpoint(String method, String path) {
        for (Endpoint endpoint : endpoints) {
            if (endpoint.matches(method, path)) {
                return endpoint;
            }
        }
        return null;
    }

    private void tooManyRequests(ServletResponse servletResponse, String message) throws IOException {
        HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
        httpResponse.setContentType("text/plain");
        httpResponse.setStatus(429);
        httpResponse.getWriter().append(message);
    }
}
