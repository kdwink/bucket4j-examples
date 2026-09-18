package com.redshiftsoft.example.webapp.servlets;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Deliberately slow endpoint, for exercising rate limits against long-running requests:
 *
 * <pre>
 *   GET /slow/{timeMs}   → 200 after sleeping timeMs
 * </pre>
 *
 * Map this servlet at {@code /slow/*}. Each request occupies a container thread for the whole delay, so
 * {@code timeMs} is capped at {@link #MAX_DELAY_MS}.
 */
public class SlowServlet extends HttpServlet {

    public static final long MAX_DELAY_MS = 60_000;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"error\":\"usage: GET /slow/{timeMs}\"}");
            return;
        }

        long timeMs;
        try {
            timeMs = Long.parseLong(pathInfo.substring(1));
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"error\":\"timeMs must be a number\"}");
            return;
        }

        if (timeMs < 0 || timeMs > MAX_DELAY_MS) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"error\":\"timeMs must be between 0 and " + MAX_DELAY_MS + "\"}");
            return;
        }

        try {
            Thread.sleep(timeMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.getWriter().write("{\"error\":\"interrupted\"}");
            return;
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write("{\"timeMs\":" + timeMs + "}");
    }

}
