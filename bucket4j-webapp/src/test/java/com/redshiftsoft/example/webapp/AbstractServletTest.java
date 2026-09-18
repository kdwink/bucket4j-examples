package com.redshiftsoft.example.webapp;

import com.redshiftsoft.example.webapp.limit.ThrottlingFilter;
import com.redshiftsoft.example.webapp.servlets.HelloServlet;
import com.redshiftsoft.example.webapp.servlets.LoginServlet;
import com.redshiftsoft.example.webapp.servlets.SlowServlet;
import com.redshiftsoft.example.webapp.servlets.UsersServlet;
import jakarta.servlet.DispatcherType;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.SessionHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.EnumSet;

/**
 * A server whose {@link ThrottlingFilter} keeps its buckets in the Valkey cluster, so these tests need one
 * running — see {@link com.redshiftsoft.example.webapp.ValkeyTestCluster}.
 */
public abstract class AbstractServletTest {

    private static Server server;
    protected static int port;

    @BeforeAll
    public static void startServer() throws Exception {
        ValkeyTestCluster.assumeAvailable();
        ValkeyTestCluster.flush();

        server = new Server(0);

        ServletContextHandler context = new ServletContextHandler("/");
        context.setSessionHandler(new SessionHandler());
        context.addFilter(ThrottlingFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(HelloServlet.class, "/hello");
        context.addServlet(LoginServlet.class, "/login");
        context.addServlet(UsersServlet.class, "/users/*");
        context.addServlet(SlowServlet.class, "/slow/*");
        server.setHandler(context);

        server.start();
        port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterAll
    public static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

}
