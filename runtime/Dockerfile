FROM tomcat:10.1-jdk21-temurin

COPY runtime/conf/server.xml /usr/local/tomcat/conf/server.xml

COPY security-valve/target/security-valve-0.0.1-SNAPSHOT.jar \
     /usr/local/tomcat/lib/security-valve-0.0.1.jar

