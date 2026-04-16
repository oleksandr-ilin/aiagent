# Security (Amazon Cognito)

The AI Agent currently accepts requests from anyone. Before deploying to production, you need to add authentication to protect the API.

Stop the AI Agent application with Ctrl+C and keep the MCP server application running.

## Introduction to security

[Amazon Cognito](https://aws.amazon.com/cognito/) provides authentication, authorization, and user management for web and mobile applications. It integrates with 
[Spring Security](https://spring.io/projects/spring-security)
to protect your API endpoints using OAuth 2.0 and JWT tokens.

The authentication flow works as follows:

1. User authenticates with Amazon Cognito and receives a JWT token
2. User sends requests to the AI Agent with the token in the Authorization header
3. Spring Security validates the token against Cognito
4. The AI Agent extracts the user identity from the token for conversation tracking

## Creating the Cognito User Pool

A [User Pool](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools.html) 
is a user directory in Amazon Cognito that provides sign-up and sign-in functionality.

1. Create an Amazon Cognito User Pool:

```
USER_POOL_ID=$(aws cognito-idp create-user-pool \
  --pool-name "aiagent-user-pool" \
  --policies '{
    "PasswordPolicy": {
      "MinimumLength": 8,
      "RequireUppercase": true,
      "RequireLowercase": true,
      "RequireNumbers": true,
      "RequireSymbols": false
    }
  }' \
  --auto-verified-attributes email \
  --username-configuration '{"CaseSensitive": false}' \
  --region ${AWS_REGION} \
  --no-cli-pager \
  --query 'UserPool.Id' --output text)
echo "User Pool ID: ${USER_POOL_ID}"
```
- 4-9: Password policy requiring 8+ characters with uppercase, lowercase, and numbers
- 12: Auto-verify email addresses

2. Create an [app client](https://docs.aws.amazon.com/cognito/latest/developerguide/user-pool-settings-client-apps.html) 
   for the AI Agent to authenticate users:

```
USER_POOL_ID=$(aws cognito-idp list-user-pools --max-results 60 --no-cli-pager \
  --query "UserPools[?Name=='aiagent-user-pool'].Id | [0]" --output text)

CLIENT_ID=$(aws cognito-idp create-user-pool-client \
  --user-pool-id "${USER_POOL_ID}" \
  --client-name "aiagent-client" \
  --no-generate-secret \
  --explicit-auth-flows ALLOW_USER_PASSWORD_AUTH ALLOW_USER_SRP_AUTH ALLOW_REFRESH_TOKEN_AUTH \
  --region ${AWS_REGION} \
  --no-cli-pager \
  --query 'UserPoolClient.ClientId' --output text)
echo "Client ID: ${CLIENT_ID}"
```
- 5: Associate client with the User Pool
- 7: Public client (no secret) for browser-based authentication

3. Create test users:
```
USER_POOL_ID=$(aws cognito-idp list-user-pools --max-results 60 --no-cli-pager \
  --query "UserPools[?Name=='aiagent-user-pool'].Id | [0]" --output text)

for USER in admin alice bob; do
  aws cognito-idp admin-create-user \
    --user-pool-id "${USER_POOL_ID}" \
    --username "${USER}" \
    --temporary-password "${IDE_PASSWORD}" \
    --message-action SUPPRESS \
    --region ${AWS_REGION} \
    --no-cli-pager

  aws cognito-idp admin-set-user-password \
    --user-pool-id "${USER_POOL_ID}" \
    --username "${USER}" \
    --password "${IDE_PASSWORD}" \
    --permanent \
    --region ${AWS_REGION} \
    --no-cli-pager
done
echo "Test users created: admin, alice, bob"
```
- 4: Create users with temporary password (suppresses welcome email)
- 13: Set permanent password to skip forced password change

4. Configure the UI for Cognito authentication by creating a config file with the User Pool and client IDs:

```
USER_POOL_ID=$(aws cognito-idp list-user-pools --max-results 60 --no-cli-pager \
  --query "UserPools[?Name=='aiagent-user-pool'].Id | [0]" --output text)
CLIENT_ID=$(aws cognito-idp list-user-pool-clients --user-pool-id "${USER_POOL_ID}" --no-cli-pager \
  --query "UserPoolClients[?ClientName=='aiagent-client'].ClientId | [0]" --output text)

cat > ~/environment/aiagent/src/main/resources/static/config.json << EOF
{
  "userPoolId": "${USER_POOL_ID}",
  "clientId": "${CLIENT_ID}",
  "region": "${AWS_REGION}",
  "apiEndpoint": "invocations"
}
EOF
```
- 8-11: JSON config file used by the Web UI to authenticate with Cognito

## Updating the configuration

1. Open application.properties:

```
code ~/environment/aiagent/src/main/resources/application.properties
```

2. Add the security configuration:

```
# Security Configuration
spring.security.oauth2.resourceserver.jwt.issuer-uri=${COGNITO_ISSUER_URI:}
```

- 2: Cognito issuer URI for JWT validation (empty disables security for local development)

### How security enforcement works:

- When COGNITO_ISSUER_URI is set: Spring Security requires valid JWT for /invocations
- When COGNITO_ISSUER_URI is empty: Spring Security permits all requests (local development)


## Adding dependencies

Add Spring Security OAuth2 Resource Server dependency to pom.xml:

```
sed -i '0,/<dependencies>/{/<dependencies>/a\
        <!-- Security dependencies -->\
        <dependency>\
            <groupId>org.springframework.boot</groupId>\
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>\
        </dependency>
}' ~/environment/aiagent/pom.xml
```
- 5: Spring Boot OAuth2 Resource Server for JWT validation

## Updating the code

1. Create SecurityConfig.java:

```
cat <<'EOF' > ~/environment/aiagent/src/main/java/com/example/agent/SecurityConfig.java
package com.example.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    private String issuerUri;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable());
        http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/", "/*.js", "/*.css", "/*.json", "/*.svg", "/*.html").permitAll()
            .requestMatchers("/actuator/**").permitAll()
        );

        if (issuerUri != null && !issuerUri.isBlank()) {
            http.authorizeHttpRequests(auth -> auth
                    .requestMatchers("/invocations").authenticated()
                    .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }
}
EOF
```
- 16-17: Read issuer URI from configuration
- 22-25: Allow public access to static files and health endpoints

2. Open InvocationController.java:

```
code ~/environment/aiagent/src/main/java/com/example/agent/InvocationController.java
```

3. Replace the file content:

```java
package com.example.agent;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@CrossOrigin(origins = "*")
public class InvocationController {
    private final ChatService chatService;

    public InvocationController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "invocations", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> handleInvocation(
            @RequestBody InvocationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return chatService.chat(request.prompt(), "default");
        }
        String visitorId = jwt.getSubject().replace("-", "").substring(0, 25);
        String sessionId = jwt.getClaim("auth_time").toString();
        return chatService.chat(request.prompt(), visitorId + ":" + sessionId);
    }

    @PostMapping(value = "load", consumes = MediaType.TEXT_PLAIN_VALUE)
    public void loadDocument(@RequestBody String content) {
        chatService.loadDocument(content);
    }
}
```

- 4-5: Import Spring Security JWT classes
- 18-19: POST endpoint with JWT injection
- 21: Inject JWT from authenticated request
- 25-26: Build conversation ID as visitorId:sessionId

## Testing the application

1. Start the application with security enabled:

```
cd ~/environment/aiagent
USER_POOL_ID=$(aws cognito-idp list-user-pools --max-results 60 --no-cli-pager \
  --query "UserPools[?Name=='aiagent-user-pool'].Id | [0]" --output text)
export COGNITO_ISSUER_URI="https://cognito-idp.${AWS_REGION}.amazonaws.com/${USER_POOL_ID}"
export SPRING_DATASOURCE_URL=$(aws ssm get-parameter --name workshop-db-connection-string --no-cli-pager \
  | jq --raw-output '.Parameter.Value')
export SPRING_DATASOURCE_USERNAME=$(aws secretsmanager get-secret-value --secret-id workshop-db-secret --no-cli-pager \
  | jq --raw-output '.SecretString' | jq -r .username)
export SPRING_DATASOURCE_PASSWORD=$(aws secretsmanager get-secret-value --secret-id workshop-db-secret --no-cli-pager \
  | jq --raw-output '.SecretString' | jq -r .password)
export SPRING_AI_MCP_CLIENT_STREAMABLEHTTP_CONNECTIONS_SERVER1_URL=http://localhost:8081
./mvnw spring-boot:run
```

2. Test without token (should fail with 401):

```
curl -v -N -X POST localhost:8080/invocations \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Hello"}'
```

Expected output:

```
HTTP/1.1 401
WWW-Authenticate: Bearer
```

3. Obtain a [JWT token](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-with-identity-providers.html) and test authenticated requests:

```
USER_POOL_ID=$(aws cognito-idp list-user-pools --max-results 60 --no-cli-pager \
  --query "UserPools[?Name=='aiagent-user-pool'].Id | [0]" --output text)
CLIENT_ID=$(aws cognito-idp list-user-pool-clients --user-pool-id "${USER_POOL_ID}" --no-cli-pager \
  --query "UserPoolClients[?ClientName=='aiagent-client'].ClientId | [0]" --output text)

TOKEN=$(aws cognito-idp initiate-auth \
  --client-id ${CLIENT_ID} \
  --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters USERNAME=alice,PASSWORD=${IDE_PASSWORD} \
  --region ${AWS_REGION} \
  --no-cli-pager \
  --query 'AuthenticationResult.IdToken' --output text)

curl -N -X POST localhost:8080/invocations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${TOKEN}" \
  -d '{"prompt": "My name is Alice, how are you?"}' ; echo

curl -N -X POST localhost:8080/invocations \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${TOKEN}" \
  -d '{"prompt": "What is my name?"}' ; echo
```

- 6-12: Authenticate with Cognito and retrieve the ID token
- 16: Send authenticated request with Bearer token in Authorization header

### Test credentials:

| Username	| Password            |
|-----------|---------------------|
| admin	    |echo ${IDE_PASSWORD} |
| alice	    |echo ${IDE_PASSWORD} |
| bob	    |echo ${IDE_PASSWORD} |

You can also use the Web UI to log out and log in with Amazon Cognito using these credentials (alice, bob).

>  REST API and Web UI use different sessions, so they have separate conversation memory.


Stop the AI Agent application and the MCP server with Ctrl+C.