package com.aka.golden_image.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HelloController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello from Golden Image!";
    }

    @GetMapping("/whoami")
    public Map<String, Object> whoami(HttpServletRequest request) {
        return Map.of(
                "principal", request.getUserPrincipal().getName(),
                "remoteUser", request.getRemoteUser(),
                "isUser", request.isUserInRole("USER"),
                "isAdmin", request.isUserInRole("ADMIN")
        );
    }
}