FROM tomcat:10.1-jdk21-temurin

COPY target/golden-image-0.0.1-SNAPSHOT.war /usr/local/tomcat/webapps/golden-image.war