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
Official Tomcat Image
tomcat:10.1-jdk21-temurin
        │
        ▼
Golden Image
company/tomcat-golden:1.2.0
        │
        ├── Java 21
        ├── Tomcat 10.1
        ├── Standardized Tomcat configuration
        └── Security Valve
                │
                ├── Mock authentication
                ├── Principal creation
                └── Role propagation
                        │
                        ▼
Application Image
golden-image-app:1.2.0
        │
        └── Spring Boot WAR
```

The Golden Image is owned by the **platform layer**, while the WAR remains an independent application artifact.

The application does not implement the authentication mechanism itself.

---

## Request Pipeline

The custom Tomcat `SecurityValve` intercepts HTTP requests at the Tomcat `Host` level before they reach the deployed application.

```text
HTTP Request
      │
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
      ├── Mock authentication
      │
      ├── GenericPrincipal
      │       └── alice
      │
      └── Roles
              ├── USER
              └── ADMIN
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

The Valve executes before the request reaches the application.

This demonstrates how platform-level security concerns can be implemented at the Tomcat container level without coupling authentication logic to the Spring Boot application.

---

## Container-Managed Identity

Version `1.2.0` introduces a container-managed identity demonstration.

The Security Valve currently performs **mock authentication** by creating a Tomcat `GenericPrincipal`:

```text
User
└── alice

Roles
├── USER
└── ADMIN
```

Conceptually:

```text
SecurityValve
      │
      ▼
Mock Authentication
      │
      ▼
GenericPrincipal
      │
      ├── alice
      ├── USER
      └── ADMIN
      │
      ▼
Tomcat Request
      │
      ▼
Spring Boot WAR
```

The identity is attached to the Tomcat request before processing continues toward the application.

The WAR therefore receives a request that already contains the authenticated identity.

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

For the current mock identity:

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

This architecture demonstrates an important enterprise pattern:

```text
Authentication mechanism
        │
        ▼
Platform Runtime
        │
        ▼
Principal
        │
        ▼
Application
```

The application depends on the **identity contract**, not directly on the authentication technology.

Today:

```text
Mock Authentication
        ↓
GenericPrincipal
```

A future version will replace the mock authentication with:

```text
Keycloak
   ↓
OIDC / OAuth2
   ↓
JWT Access Token
   ↓
SecurityValve
   ↓
GenericPrincipal
```

while keeping the WAR largely independent from the authentication implementation.

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
company/tomcat-golden:1.2.0
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
* performs mock authentication;
* creates a Tomcat `GenericPrincipal`;
* associates the roles `USER` and `ADMIN`;
* attaches the Principal to the request;
* adds an `X-Security-Valve` HTTP response header;
* forwards the request to the next component in the Tomcat pipeline.

The important continuation operation remains:

```java
getNext().invoke(request, response);
```

which continues request processing through the Tomcat pipeline.

The module produces:

```text
security-valve-0.0.1-SNAPSHOT.jar
```

The JAR is installed in the Tomcat shared library directory:

```text
/usr/local/tomcat/lib/
```

This makes the Valve part of the Tomcat platform rather than part of an individual WAR.

The Valve is configured at the Tomcat `Host` level in `server.xml`.

As a result, it can intercept requests before applications deployed on that Host.

---

## `app/`

Contains the Spring Boot application and its Dockerfile.

The application is packaged as an external-container-compatible WAR:

```text
app-0.0.1-SNAPSHOT.war
```

The application image inherits from the Golden Image:

```dockerfile
FROM company/tomcat-golden:1.2.0
```

and only adds the WAR:

```text
company/tomcat-golden:1.2.0
        +
Spring Boot WAR
        │
        ▼
golden-image-app:1.2.0
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
  -t company/tomcat-golden:1.2.0 \
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
  -t golden-image-app:1.2.0 \
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
  golden-image-app:1.2.0
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

## Verify the Security Valve

### Verify Request Interception

Call:

```bash
curl -i http://localhost:8081/golden-image/hello
```

The HTTP response contains:

```text
X-Security-Valve: active
```

This proves that the request passed through the platform-level Valve before reaching the Spring Boot application.

### Verify Container-Managed Identity

Call:

```bash
curl -i http://localhost:8081/golden-image/whoami
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

This proves the complete identity propagation flow:

```text
SecurityValve
      ↓
GenericPrincipal
      ↓
Tomcat Request
      ↓
Spring Boot WAR
      ↓
HttpServletRequest
```

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

The project currently demonstrates three architectural milestones:

```text
1.0.0
  │
  └── Baseline Golden Image
        │
        ▼
1.1.0
  │
  └── Platform-level Security Valve
        │
        ▼
1.2.0
  │
  └── Container-managed identity
        ├── GenericPrincipal
        └── Roles
```

Git releases and Golden Image versions can be aligned:

```text
Git tag 1.2.0
        ↕
company/tomcat-golden:1.2.0
```

Application versions remain conceptually independent from runtime versions.

---

## Roadmap

The current implementation deliberately uses a mock identity to demonstrate the contract between Tomcat and the deployed WAR.

The next architectural milestone is:

```text
Keycloak
    │
    ▼
OIDC / OAuth2
    │
    ▼
JWT Access Token
    │
    ▼
SecurityValve
    │
    ├── Bearer Token extraction
    ├── JWT validation
    ├── signature verification
    ├── issuer validation
    ├── audience validation
    ├── expiration validation
    └── role extraction
            │
            ▼
GenericPrincipal
            │
            ▼
Spring Boot WAR
```

The objective is to replace mock authentication while preserving the same application-facing identity contract.

---

## Goal

This project reproduces an enterprise Platform Engineering pattern where a platform team provides standardized and secured application runtimes while development teams remain responsible for their application artifacts.

The architecture currently demonstrates:

```text
Platform Team
     │
     ├── Java runtime
     ├── Tomcat runtime
     ├── Tomcat configuration
     ├── Security Valve
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
              └── consumes Principal
```

Release `1.2.0` extends the platform-level Security Valve with a mock authenticated identity and Tomcat-managed roles.

The Spring Boot WAR remains independent from the authentication implementation and consumes the identity through the standard Servlet API.

No external Identity Provider is integrated yet.

The next release will replace the mock identity with standards-based authentication using **Keycloak, OpenID Connect, OAuth 2.0 and JWT access tokens**.
