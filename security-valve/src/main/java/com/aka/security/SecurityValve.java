package com.aka.security;

import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.realm.GenericPrincipal;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;
import java.security.Principal;
import java.util.List;

public class SecurityValve extends ValveBase {

    @Override
    public void invoke(Request request, Response response)
            throws IOException, ServletException {

        Principal principal = new GenericPrincipal(
                "alice",
                List.of("USER", "ADMIN")
        );

        request.setUserPrincipal(principal);

        System.out.println(
                "[SecurityValve] Request intercepted: "
                        + request.getUserPrincipal().getName()
        );

        response.setHeader("X-Security-Valve", "active");

        getNext().invoke(request, response);
    }
}