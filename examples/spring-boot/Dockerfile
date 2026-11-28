FROM eclipse-temurin:17-jre
WORKDIR /app
COPY build/libs/app.jar app.jar
COPY python python
RUN java -jar app.jar --spring.main.web-application-type=none
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
