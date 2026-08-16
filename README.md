# Tomcat Golden Image

A reference implementation of an enterprise-style **Tomcat Golden Image** used to standardize the runtime environment for Spring Boot WAR applications.

The project demonstrates the separation between:

* a **platform-managed Tomcat runtime**;
* a **platform-level Tomcat Security Valve**;
* an **application image** containing the Spring Boot WAR.

## Architecture

```text
Official Tomcat Image
tomcat:10.1-jdk21-temurin
        │
        ▼
Golden Image
company/tomcat-golden:1.1.0
        │
        ├── Java 21
        ├── Tomcat 10.1
        ├── Standardized Tomcat configuration
        └── Security Valve
                │
                ▼
Application Image
golden-image-app:1.1.0
        │
        └── Spring Boot WAR
```

The Golden Image is owned by the platform layer, while the WAR remains an independent application artifact.

## Request Pipeline

Version `1.1.0` introduces a custom Tomcat Valve that intercepts HTTP requests at the Tomcat `Host` level.

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

This demonstrates how cross-cutting platform concerns can be implemented at the Tomcat container level without coupling them to the Spring Boot application.

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

### `runtime/`

Contains the platform-managed Tomcat Golden Image.

The runtime is based on:

```text
tomcat:10.1-jdk21-temurin
```

It provides:

* Java 21
* Apache Tomcat 10.1
* Version-controlled Tomcat configuration
* Platform-level Security Valve

The runtime image is built as:

```text
company/tomcat-golden:1.1.0
```

### `security-valve/`

Contains the custom Tomcat Valve.

The Valve is implemented using the Tomcat Catalina API and extends:

```java
ValveBase
```

Its current implementation intentionally remains simple. It:

* logs intercepted requests;
* adds an `X-Security-Valve` HTTP response header;
* forwards the request to the next component in the Tomcat pipeline.

The important operation is:

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

As a result, it can intercept requests for all applications deployed on that Host.

### `app/`

Contains the Spring Boot application and its Dockerfile.

The application is packaged as an external-container-compatible WAR:

```text
app-0.0.1-SNAPSHOT.war
```

The application image inherits from the Golden Image:

```dockerfile
FROM company/tomcat-golden:1.1.0
```

and only adds the WAR:

```text
company/tomcat-golden:1.1.0
        +
Spring Boot WAR
        │
        ▼
golden-image-app:1.1.0
```

This keeps the platform lifecycle separated from the application lifecycle.

## Build

### Build Maven artifacts

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
  -t company/tomcat-golden:1.1.0 \
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

### Build the application image

```bash
docker build \
  -f app/Dockerfile \
  -t golden-image-app:1.1.0 \
  .
```

The resulting image inherits the platform runtime and adds the application WAR.

## Run

Run the application:

```bash
docker run \
  --name golden-tomcat-demo \
  -p 8081:8080 \
  golden-image-app:1.1.0
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
      ▼
golden-image.war
      │
      ▼
Spring Boot
```

## Verify the Security Valve

Call the application endpoint:

```bash
curl -i http://localhost:8081/golden-image/hello
```

Tomcat logs should show the Valve intercepting the request:

```text
[SecurityValve] Request intercepted: /golden-image/hello
```

The HTTP response also contains:

```text
X-Security-Valve: active
```

This proves that the request passed through the platform-level Valve before reaching the Spring Boot application.

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

## Valve vs Application Filter

The Security Valve deliberately lives outside the application.

```text
Tomcat
│
├── SecurityValve        Platform concern
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

## Versioning

The Golden Image follows semantic versioning:

```text
MAJOR.MINOR.PATCH
```

The initial stable runtime was:

```text
company/tomcat-golden:1.0.0
```

Version `1.1.0` introduces the platform-level Security Valve:

```text
1.0.0
  │
  └── Baseline Golden Image

1.1.0
  │
  └── Tomcat Security Valve
```

Git releases and Golden Image versions can be aligned:

```text
Git tag 1.1.0
        ↕
company/tomcat-golden:1.1.0
```

Application versions remain conceptually independent from runtime versions.

## Goal

This project reproduces an enterprise Platform Engineering pattern where a platform team provides standardized application runtimes while development teams remain responsible for their application artifacts.

The architecture currently demonstrates:

```text
Platform Team
     │
     ├── Java runtime
     ├── Tomcat runtime
     ├── Tomcat configuration
     └── Security Valve
              │
              ▼
       Golden Image
              │
              ▼
Application Team
     │
     └── Spring Boot WAR
```

Release `1.1.0` extends the baseline Golden Image with a custom Tomcat Security Valve executed at the `Host` level, demonstrating **platform-level HTTP request interception independently of application code**.

No real authentication or authorization mechanism is implemented yet. The current Valve intentionally focuses on understanding the Tomcat request pipeline and establishing the platform extension mechanism.
