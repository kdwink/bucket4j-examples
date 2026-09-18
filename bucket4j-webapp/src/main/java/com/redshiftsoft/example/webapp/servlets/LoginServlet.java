package com.redshiftsoft.example.webapp.servlets;

import com.redshiftsoft.example.webapp.model.User;
import com.redshiftsoft.example.webapp.model.UserRepository;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Optional;

public class LoginServlet extends HttpServlet {

    public static final String USER_SESSION_KEY = "user";

    private final UserRepository userRepository = new UserRepository();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username = req.getParameter("username");
        String password = req.getParameter("password");

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"username and password are required\"}");
            return;
        }

        Optional<User> user = userRepository.authenticate(username, password);
        if (user.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"invalid credentials\"}");
            return;
        }

        req.getSession().setAttribute(USER_SESSION_KEY, user.get());

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"username\":\"" + username + "\"}");
    }

}
