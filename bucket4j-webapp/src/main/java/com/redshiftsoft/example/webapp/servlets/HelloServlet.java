package com.redshiftsoft.example.webapp.servlets;

import com.redshiftsoft.example.webapp.model.User;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class HelloServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String greeting = "Hello, World!";
        User user = (User) req.getSession().getAttribute(LoginServlet.USER_SESSION_KEY);
        if (user != null) {
            greeting = "Hello, " + user.username() + "!";
        }

        resp.setContentType("text/html");
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write("<html><body><h1>" + greeting + "</h1></body></html>");
    }

}
