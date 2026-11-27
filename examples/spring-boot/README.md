# Spring Boot service

A REST endpoint backed by a Python function, wired by `jpyrust-spring-boot-starter`: the bridge is configured from `application.yml`, calls go through `JPyRustTemplate`, Micrometer records `jpyrust.calls`, and `/actuator/health` reports the workers.

```bash
make native                                  # from the repository root, once
./gradlew :examples:spring-boot:bootRun

curl -s -X POST localhost:8080/sentiment -d 'the new build is fast and great'
curl -s localhost:8080/actuator/health
curl -s 'localhost:8080/actuator/metrics/jpyrust.calls?tag=task:SENTIMENT'
```

## Docker

```bash
./gradlew :examples:spring-boot:bootJar
docker build -t jpyrust-sentiment examples/spring-boot
docker run -p 8080:8080 jpyrust-sentiment
```

The `RUN` step starts the app once without a web server, so Python and the venv are downloaded at build time and baked into the image. A JAR built from this repository carries the natives committed for Linux x86_64 only; on an arm64 host, build with `--platform linux/amd64` or use the JAR from a GitHub Release, which carries all five platforms.
