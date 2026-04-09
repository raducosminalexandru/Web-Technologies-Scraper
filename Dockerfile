# Use a lightweight Java 17 image
FROM eclipse-temurin:17-jre-jammy

# Create a directory for the app
WORKDIR /app

# Copy the Fat JAR from the target folder to the container
# Note: Ensure the name matches what sbt assembly produces
COPY target/scala-2.13/*.jar app.jar

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]