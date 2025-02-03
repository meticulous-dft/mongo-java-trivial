# Conatiner is arch specific. For Fargate use --platform linux/amd64.
# Emulation overhead is significant - if you have 1GB ram for Docker I'm not sure it completes (gave up after 5 minutes). It will probably work w 8GB
# Restart if it fails - build should take <2m

# Use an official OpenJDK runtime as a parent image
FROM openjdk:17-jdk-slim AS build

# Install Maven
RUN apt-get update && apt-get install -y maven

# Set the working directory in the container
WORKDIR /app

# Copy the source code into the container
COPY src /app/src
COPY pom.xml /app

# Compile the Java program
RUN mvn package

# Use a smaller runtime image
FROM openjdk:17-slim

# Set the working directory in the container
WORKDIR /app

# Copy the jar file from the build stage
COPY --from=build /app/target/mt.jar /app/mt.jar

# Run the jar file with the argument
ENTRYPOINT ["java", "-jar"]
CMD ["./mt.jar", "-a","<CONNECTION_STRING>", "-w", "0", "-wt", "1", "-wq", "4000", "-rt", "1", "-rq", "4000", "-st", "4000", "-mc", "1", "-ci", "1", "-ee", "<REPORT_STRING>", "-f", "<FRONT_END_LB_ADDERSS>","-p", "<LB_PORTS>"]
