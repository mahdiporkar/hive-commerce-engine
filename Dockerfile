# Hive Commerce Engine image. Clients build FROM this image by digest, add their extension JARs to /app/extensions and
# provide configuration through the environment (ADR-0012). Build after: ./mvnw -B clean install
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S commerce && adduser -S commerce -G commerce && mkdir -p /app/extensions && chown -R commerce:commerce /app
COPY commerce-app/target/commerce-engine.jar /app/commerce-engine.jar
USER commerce
WORKDIR /app
ENV LOADER_PATH=/app/extensions
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=3s --retries=20 CMD wget -qO- http://127.0.0.1:8090/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/commerce-engine.jar"]
