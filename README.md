# Tomcat Golden Image

A reference implementation of an enterprise-style **Tomcat Golden Image** used to standardize the runtime environment for Spring Boot WAR applications.

The project demonstrates the separation between a **platform-managed Tomcat runtime** and an **application image** containing the WAR.

## Architecture

```text
Official Tomcat Image
tomcat:10.1-jdk21-temurin
        │
        ▼
Golden Image
company/tomcat-golden:1.0.0
        │
        ├── Java 21
        ├── Tomcat 10.1
        └── Standardized Tomcat configuration
                │
                ▼
Application Image
golden-image-app
        │
        └── Spring Boot WAR
```

## Project Structure

```text
golden-image/
│
├── runtime/
│   ├── Dockerfile
│   └── conf/
│       └── server.xml
│
├── app/
│   └── Dockerfile
│
├── src/
├── pom.xml
├── .gitignore
├── .dockerignore
└── README.md
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

### `app/`

Contains the Dockerfile used by the application team.

The application image inherits from the Golden Image and only adds the Spring Boot WAR:

```text
company/tomcat-golden:1.0.0
        +
Spring Boot WAR
```

This keeps the runtime lifecycle separated from the application lifecycle.

## Build

Build the Spring Boot WAR:

```bash
mvn clean package
```

Build the Golden Image:

```bash
docker build \
  -t company/tomcat-golden:1.0.0 \
  runtime/
```

Build the application image:

```bash
docker build \
  -f app/Dockerfile \
  -t golden-image-app:1.0.0 \
  .
```

## Run

Run the application:

```bash
docker run \
  --name golden-tomcat-demo \
  -p 8081:8080 \
  golden-image-app:1.0.0
```

The mapping is:

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
golden-image.war
      │
      ▼
Spring Boot
```

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
lib/       Runtime shared libraries
webapps/   WAR deployments
logs/      Tomcat logs
temp/      Temporary files
work/      Tomcat working files
```

## Versioning

The Golden Image follows semantic versioning:

```text
MAJOR.MINOR.PATCH
```

Example:

```text
company/tomcat-golden:1.0.0
```

Git releases and Golden Image versions are aligned:

```text
Git tag 1.0.0
        ↕
company/tomcat-golden:1.0.0
```

Application versions remain independent from the runtime version.

## Goal

This project reproduces an enterprise Platform Engineering pattern where a platform team provides standardized application runtimes while development teams remain responsible for their application artifacts.

The current `1.0.0` release establishes the baseline Tomcat Golden Image.
