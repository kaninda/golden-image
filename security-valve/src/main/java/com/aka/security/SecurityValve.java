package com.aka.security;

import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;

public class SecurityValve extends ValveBase {

    @Override
    public void invoke(Request request, Response response)
            throws IOException, ServletException {

        System.out.println(
                "[SecurityValve] Request intercepted: "
                        + request.getRequestURI()
        );

        response.setHeader("X-Security-Valve", "active");

        getNext().invoke(request, response);
    }
}