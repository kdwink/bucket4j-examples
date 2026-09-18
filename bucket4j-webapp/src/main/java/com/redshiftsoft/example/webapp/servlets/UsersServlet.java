package com.redshiftsoft.example.webapp.servlets;

import com.redshiftsoft.example.webapp.model.User;
import com.redshiftsoft.example.webapp.model.UserRepository;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Read-only JSON user endpoints, for exercising per-endpoint rate limits:
 *
 * <pre>
 *   GET /users            → all users
 *   GET /users/{userId}   → a single user, 404 if unknown
 * </pre>
 *
 * Map this servlet at {@code /users/*}, which matches {@code /users} as well.
 */
public class UsersServlet extends HttpServlet {

    private final UserRepository userRepository = new UserRepository();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            List<User> users = userRepository.findAll();
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(users.stream().map(UsersServlet::toJson).collect(Collectors.joining(",", "[", "]")));
            return;
        }

        long userId;
        try {
            userId = Long.parseLong(pathInfo.substring(1));
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"error\":\"userId must be a number\"}");
            return;
        }

        Optional<User> user = userRepository.findByUserId(userId);
        if (user.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            resp.getWriter().write("{\"error\":\"no user with userId " + userId + "\"}");
            return;
        }

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(toJson(user.get()));
    }

    /** Note the password hash is deliberately left out. */
    private static String toJson(User user) {
        return "{\"userId\":" + user.userId()
                + ",\"username\":\"" + user.username() + "\""
                + ",\"firstName\":\"" + user.firstName() + "\""
                + ",\"lastName\":\"" + user.lastName() + "\""
                + ",\"organizationId\":" + user.organizationId() + "}";
    }

}
