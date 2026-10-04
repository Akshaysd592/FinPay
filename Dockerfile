FROM eclipse-temurin:21.0.9_10-jre-alpine
ARG SERVICE
RUN addgroup -S finpay && adduser -S finpay -G finpay
WORKDIR /app
COPY --chown=finpay:finpay ${SERVICE}/build/libs/app.jar /app/app.jar
USER finpay
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
