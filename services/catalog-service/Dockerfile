FROM eclipse-temurin:17-jre-alpine AS builder

WORKDIR /app

COPY target/*.jar app.jar

RUN jlink --add-modules java.base,java.logging,java.sql,java.transaction,jdk.unsupported \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /custom-jre

FROM alpine:3.20

RUN addgroup -S spring && adduser -S spring -G spring

USER spring:spring

WORKDIR /app

COPY --from=builder /custom-jre /custom-jre
COPY --from=builder /app/app.jar app.jar

ENV PATH="/custom-jre/bin:$PATH"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
