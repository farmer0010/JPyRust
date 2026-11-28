# Spring Boot service

A REST endpoint backed by a Python function, and a Server-Sent Events endpoint fed by a Python generator, wired by `jpyrust-spring-boot-starter`: the bridge is configured from `application.yml`, calls go through `JPyRustTemplate`, Micrometer records `jpyrust.calls`, and `/actuator/health` reports the workers.

```bash
make native                                  # from the repository root, once
./gradlew :examples:spring-boot:bootRun

curl -s -X POST localhost:8080/sentiment -H 'Content-Type: text/plain' -d 'the new build is fast and great'
curl -sN -X POST localhost:8080/sentiment/stream -H 'Content-Type: text/plain' \
  -d 'The build is fast. The docs are bad. I love it!'
curl -s localhost:8080/actuator/health
curl -s 'localhost:8080/actuator/metrics/jpyrust.calls?tag=task:SENTIMENT'
```

## Docker

```bash
./gradlew :examples:spring-boot:bootJar
docker build -t jpyrust-sentiment examples/spring-boot
docker run -p 8080:8080 jpyrust-sentiment
```

The `RUN` step starts the app once without a web server, so Python and the venv are downloaded at build time and baked into the image. The image works with Docker's default 64 MB `/dev/shm`: payloads that do not fit are passed through a file for that call.

A JAR built from this repository carries the natives committed for Linux x86_64, macOS arm64 and Windows x86_64. On an arm64 host (Apple Silicon, Graviton), build for arm64 with the Linux aarch64 native from a CI run or GitHub Release. Emulating `--platform linux/amd64` on Apple Silicon fails while extracting Python, because the base image's `tar` hits syscalls the emulator does not implement.

```bash
gh run download <run-id> -n native-linux-aarch64 -D java-api/src/main/resources/natives
./gradlew :examples:spring-boot:bootJar
docker build -t jpyrust-sentiment examples/spring-boot
```

Send large bodies as `text/plain`: Tomcat caps form-encoded bodies (`curl -d` without a content type) at 2 MB.
