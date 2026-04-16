FROM public.ecr.aws/docker/library/maven:3-amazoncorretto-25-al2023 AS builder

COPY ./pom.xml ./pom.xml
COPY src ./src/

RUN rm -rf src/main/resources/static
RUN mvn clean package -DskipTests -ntp && mv target/*.jar app.jar

FROM public.ecr.aws/docker/library/amazoncorretto:25-al2023

RUN yum install -y shadow-utils

RUN groupadd --system spring -g 1000
RUN adduser spring -u 1000 -g 1000

COPY --from=builder app.jar app.jar

USER 1000:1000
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app.jar"]
