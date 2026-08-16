# Tomcat Golden Image

A reference implementation of an enterprise-style **Tomcat Golden Image** used to standardize the runtime environment and platform-level security concerns for Spring Boot WAR applications.

The project demonstrates the separation between:

* a **platform-managed Tomcat runtime**;
* a **platform-level Tomcat Security Valve**;
* **container-managed identity and roles**;
* an **application image** containing the Spring Boot WAR.

The goal is to reproduce and progressively modernize an enterprise architecture where authentication and other cross-cutting concerns are handled by the application platform rather than individually by every deployed application.

## Architecture

```text
Keycloak
   │
   │ OIDC / OAuth 2.0
   ▼
JWT Access Token
   │
   │ Authorization: Bearer <token>
   ▼
Official Tomcat Image
tomcat:10.1-jdk21-temurin
   │
   ▼
Golden Image
company/tomcat-golden:1.3.0
   │
   ├── Java 21
   ├── Tomcat 10.1
   ├── Standardized Tomcat configuration
   └── Security Valve
          │
          ├── Bearer Token extraction
          ├── Nimbus JOSE + JWT
          ├── JWKS public-key retrieval
          ├── RS256 signature validation
          ├── issuer / audience / expiration validation
          ├── Keycloak role extraction
          └── GenericPrincipal creation
                    │
                    ▼
Application Image
golden-image-app:1.3.0
   │
   └── Spring Boot WAR
```

The Golden Image is owned by the **platform layer**, while the WAR remains an independent application artifact.

The application does not implement the authentication mechanism itself and intentionally does **not** use Spring Security for this demonstration.

---

## Request Pipeline

The custom Tomcat `SecurityValve` intercepts HTTP requests at the Tomcat `Host` level before they reach the deployed application.

```text
HTTP Request
   │
   │ Authorization: Bearer <JWT>
   ▼
Tomcat Connector
   │
   ▼
Engine
   │
   ▼
Host
   │
   ▼
SecurityValve
   │
   ├── Extract Bearer Token
   ├── Read JWT header (`kid`, `alg`)
   ├── Resolve public key through JWKS
   ├── Verify RS256 signature
   ├── Validate `iss`, `aud`, `exp`
   ├── Extract `preferred_username`
   ├── Extract `realm_access.roles`
   └── Create Tomcat `GenericPrincipal`
   │
   ▼
Context
   │
   ▼
Spring Boot WAR
   │
   ▼
DispatcherServlet
   │
   ▼
Controller
```

The Valve executes before the request reaches the application. A request without a Bearer Token, or with an invalid token, is rejected with `401 Unauthorized`.

This keeps authentication and JWT validation at the platform layer while preserving a standard Servlet identity contract for the WAR.

---

## Container-Managed Identity

Version `1.3.0` replaces the previous Keycloak identity with a real identity issued by Keycloak.

A valid Keycloak access token contains the identity and realm roles used by the platform:

```text
preferred_username
└── alice

realm_access.roles
├── USER
└── ADMIN
```

Conceptually:

```text
Keycloak
   ↓
JWT Access Token
   ↓
SecurityValve
   ↓
Nimbus JWT validation
   ↓
Keycloak claims
   ↓
GenericPrincipal
   ├── alice
   ├── USER
   └── ADMIN
   ↓
Tomcat Request
   ↓
Spring Boot WAR
```

The identity is attached to the Tomcat request only after the JWT has been successfully validated.

The WAR therefore receives a request that already contains the authenticated identity and roles.

---

## Principal and Roles

Tomcat exposes the authenticated identity through the standard Servlet API.

The application can access:

```java
request.getUserPrincipal();
```

which returns the authenticated `Principal`.

It can also retrieve the username using:

```java
request.getRemoteUser();
```

and check container-managed roles using:

```java
request.isUserInRole("USER");
request.isUserInRole("ADMIN");
```

For the current Keycloak identity:

```text
Principal
└── alice

Roles
├── USER
└── ADMIN
```

the application observes:

```text
getUserPrincipal().getName() → alice
getRemoteUser()              → alice
isUserInRole("USER")         → true
isUserInRole("ADMIN")        → true
```

The Spring Boot application does not need to know how the identity was created.

Its contract with the platform is simply:

```text
Platform
   │
   └── provides authenticated Principal
                │
                ▼
Application
   │
   └── consumes Principal and roles
```

---

## Why This Matters

This architecture demonstrates an enterprise Platform Engineering pattern in which the application depends on an **identity contract**, rather than directly on a specific authentication implementation.

```text
Keycloak / OIDC / OAuth 2.0
          │
          ▼
      JWT Access Token
          │
          ▼
   Platform Runtime
          │
          ▼
  Tomcat Principal
          │
          ▼
     Application
```

The previous milestone used a mock `GenericPrincipal`. Version `1.3.0` preserves the same application-facing contract while replacing the mock with standards-based authentication.

The WAR remains largely independent from Keycloak: it consumes `Principal`, `getRemoteUser()` and container-managed roles through the Servlet API.

---

## Spring Boot Application

The application intentionally does **not** use Spring Security for the current identity propagation demonstration.

The controller accesses identity directly through `HttpServletRequest`.

Example endpoint:

```text
GET /golden-image/whoami
```

Example response:

```json
{
  "principal": "alice",
  "remoteUser": "alice",
  "isUser": true,
  "isAdmin": true
}
```

This proves that the identity originates from the Tomcat container and is propagated to the WAR.

---

## Project Structure

The project is organized as a Maven multi-module project:

```text
golden-image/
│
├── pom.xml
│
├── security-valve/
│   ├── pom.xml
│   └── src/
│
├── app/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│
├── runtime/
│   ├── Dockerfile
│   └── conf/
│       └── server.xml
│
├── .gitignore
├── .dockerignore
└── README.md
```

The Maven reactor contains two modules:

```text
golden-image
│
├── app
│   └── produces app-0.0.1-SNAPSHOT.war
│
└── security-valve
    └── produces security-valve-0.0.1-SNAPSHOT.jar
```

---

## `runtime/`

Contains the platform-managed Tomcat Golden Image.

The runtime is based on:

```text
tomcat:10.1-jdk21-temurin
```

It provides:

* Java 21
* Apache Tomcat 10.1
* version-controlled Tomcat configuration
* platform-level Security Valve
* container-managed identity infrastructure

The runtime image is built as:

```text
company/tomcat-golden:1.3.0
```

---

## `security-valve/`

Contains the custom Tomcat Valve.

The Valve is implemented using the Tomcat Catalina API and extends:

```java
ValveBase
```

The current implementation:

* intercepts HTTP requests;
* extracts the `Authorization: Bearer <JWT>` header;
* validates Keycloak JWT access tokens using **Nimbus JOSE + JWT**;
* resolves Keycloak public keys from its **JWKS** endpoint;
* selects the correct public key using the JWT `kid`;
* accepts `RS256` signed tokens;
* validates the token signature, issuer, audience and expiration;
* extracts `preferred_username`;
* extracts Keycloak realm roles from `realm_access.roles`;
* creates a Tomcat `GenericPrincipal`;
* attaches the Principal to the request;
* adds an `X-Security-Valve` response header;
* forwards the request to the next component in the Tomcat pipeline.

The important continuation operation remains:

```java
getNext().invoke(request, response);
```

The Valve uses **Nimbus JOSE + JWT** rather than implementing JWT parsing or cryptographic verification manually.

Because the Valve is loaded from Tomcat's shared classloader, the module is packaged as a **shaded / fat JAR** so that Nimbus and its runtime dependencies are available alongside the Valve. Tomcat and Jakarta APIs remain `provided` dependencies and are supplied by the runtime.

The JAR is installed in:

```text
/usr/local/tomcat/lib/
```

This makes authentication a platform concern rather than part of an individual WAR.

---

## `app/`

Contains the Spring Boot application and its Dockerfile.

The application is packaged as an external-container-compatible WAR:

```text
app-0.0.1-SNAPSHOT.war
```

The application image inherits from the Golden Image:

```dockerfile
FROM company/tomcat-golden:1.3.0
```

and only adds the WAR:

```text
company/tomcat-golden:1.3.0
        +
Spring Boot WAR
        │
        ▼
golden-image-app:1.3.0
```

This keeps the platform lifecycle separated from the application lifecycle.

---

## Build

### Build Maven Artifacts

From the project root:

```bash
mvn clean package
```

This produces:

```text
app/target/app-0.0.1-SNAPSHOT.war

security-valve/target/security-valve-0.0.1-SNAPSHOT.jar
```

### Build the Golden Image

The Docker build context must be the project root because the runtime image includes the Security Valve JAR:

```bash
docker build \
  -f runtime/Dockerfile \
  -t company/tomcat-golden:1.3.0 \
  .
```

The resulting image contains:

```text
Tomcat 10.1
    +
Java 21
    +
server.xml
    +
security-valve.jar
```

### Build the Application Image

```bash
docker build \
  -f app/Dockerfile \
  -t golden-image-app:1.3.0 \
  .
```

The resulting image inherits the platform runtime and adds the application WAR.

---

## Run

Run the application:

```bash
docker run \
  --name golden-tomcat-demo \
  -p 8081:8080 \
  golden-image-app:1.3.0
```

The request flow is:

```text
localhost:8081
      │
      ▼
Docker
8081 → 8080
      │
      ▼
Tomcat Connector :8080
      │
      ▼
SecurityValve
      │
      ├── Principal
      └── Roles
      │
      ▼
golden-image.war
      │
      ▼
Spring Boot
```

---

## Verify Keycloak Authentication

### Request Without a Bearer Token

```bash
curl -i http://localhost:8081/golden-image/whoami
```

Expected result:

```text
HTTP/1.1 401
```

### Request With a Valid Keycloak Access Token

```bash
curl -i \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/golden-image/whoami
```

Expected response:

```json
{
  "principal": "alice",
  "remoteUser": "alice",
  "isUser": true,
  "isAdmin": true
}
```

This proves the complete authentication and identity propagation flow:

```text
Keycloak
   ↓
Access Token JWT
   ↓
SecurityValve
   ↓
JWKS + signature / claims validation
   ↓
GenericPrincipal
   ↓
Tomcat Request
   ↓
Spring Boot WAR
   ↓
HttpServletRequest
```

For the current Docker Desktop lab, Keycloak is published on the host at `localhost:8080` while Tomcat runs in a separate container. The Valve therefore reaches the JWKS endpoint through `host.docker.internal:8080`; the token issuer remains the Keycloak issuer exposed as `http://localhost:8080/realms/tomcat-platform`.

---

## Runtime Layout

The Tomcat runtime is located under:

```text
/usr/local/tomcat
```

with:

```text
CATALINA_HOME=/usr/local/tomcat
CATALINA_BASE=/usr/local/tomcat
```

Main directories:

```text
bin/       Tomcat startup scripts
conf/      Tomcat configuration
lib/       Runtime shared libraries and Security Valve
webapps/   WAR deployments
logs/      Tomcat logs
temp/      Temporary files
work/      Tomcat working files
```

The resulting separation is:

```text
/usr/local/tomcat/
│
├── conf/
│   └── server.xml
│
├── lib/
│   └── security-valve-0.0.1.jar
│
└── webapps/
    └── golden-image.war
```

The Valve therefore belongs to the **Tomcat runtime**, while the WAR belongs to the **application layer**.

---

## Valve vs Application Filter

The Security Valve deliberately lives outside the application.

```text
Tomcat
│
├── SecurityValve
│      ├── authentication
│      ├── identity
│      └── roles
│
└── Context
       │
       └── WAR
            │
            ├── Servlet Filters
            ├── Spring
            └── Controllers
```

A Tomcat Valve uses Tomcat-specific APIs such as `org.apache.catalina.*` and is therefore coupled to Tomcat.

A Jakarta Servlet Filter belongs to the web application and uses the standard Servlet API.

This distinction allows platform-level concerns to remain independent from application implementation details.

---

## Versioning

The Golden Image follows semantic versioning:

```text
MAJOR.MINOR.PATCH
```

The project currently demonstrates four architectural milestones:

```text
1.0.0
  └── Baseline Golden Image
        ↓
1.1.0
  └── Platform-level Security Valve
        ↓
1.2.0
  └── Container-managed Keycloak identity
        ├── GenericPrincipal
        └── Roles
        ↓
1.3.0
  └── Keycloak + OIDC/JWT
        ├── Bearer Token
        ├── Nimbus JOSE + JWT
        ├── JWKS
        ├── JWT validation
        └── Keycloak identity → Principal
```

Git releases and Golden Image versions can be aligned:

```text
Git tag 1.3.0
        ↕
company/tomcat-golden:1.3.0
```

Application versions remain conceptually independent from runtime versions.

---

## Roadmap

The Keycloak / OIDC / JWT integration is now functional.

Current flow:

```text
Keycloak
    │
    ▼
OIDC / OAuth 2.0
    │
    ▼
JWT Access Token
    │
    ▼
SecurityValve
    │
    ├── Bearer Token extraction
    ├── JWKS key resolution
    ├── RS256 signature verification
    ├── issuer validation
    ├── audience validation
    ├── expiration validation
    ├── username extraction
    └── role extraction
            │
            ▼
GenericPrincipal
            │
            ▼
Spring Boot WAR
```

The next hardening milestone is to industrialize the platform component:

* externalize `issuer`, `audience` and JWKS configuration;
* construct and reuse the Nimbus `JWTProcessor` rather than rebuilding it per request;
* separate Tomcat request orchestration from JWT validation;
* add automated positive and negative authentication tests;
* run Keycloak and the Golden Image on a shared Docker network;
* later deploy the architecture on Kubernetes.

---

## Goal

This project reproduces an enterprise Platform Engineering pattern where a platform team provides standardized and secured application runtimes while development teams remain responsible for their application artifacts.

The architecture now demonstrates:

```text
Platform Team
     │
     ├── Java runtime
     ├── Tomcat runtime
     ├── Tomcat configuration
     ├── Security Valve
     ├── OIDC/JWT validation
     └── Container-managed identity
              │
              ▼
         Golden Image
              │
              ▼
Application Team
     │
     └── Spring Boot WAR
              │
              └── consumes Principal and roles
```

Release `1.3.0` replaces the OIDC/JWT authentication milestone with real standards-based authentication using **Keycloak, OpenID Connect, OAuth 2.0, JWT access tokens, JWKS and Nimbus JOSE + JWT**.

The Spring Boot WAR remains independent from the authentication implementation and consumes the authenticated identity through the standard Servlet API.

The resulting target flow is now operational:

```text
JWT Keycloak
    ↓
Tomcat Security Valve
    ↓
Principal
    ↓
Spring Boot WAR
```
