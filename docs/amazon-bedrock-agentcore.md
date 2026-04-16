# Amazon Bedrock AgentCore

## Introduction to Amazon Bedrock AgentCore

[Amazon Bedrock AgentCore](https://aws.amazon.com/bedrock/agentcore/) is a fully managed, serverless platform for deploying and operating AI agents at scale. Amazon Bedrock AgentCore handles the undifferentiated heavy lifting of agent infrastructure - scaling, security, networking, and operational management - so you can focus on building agent capabilities.


### Amazon Bedrock AgentCore:

- Runs your agent containers on fully managed infrastructure with automatic scaling based on demand
- Provides built-in security with JWT authentication, IAM integration, and VPC connectivity
- Supports streaming responses natively for real-time AI interactions
- Integrates with Amazon Cognito for user authentication and session management
- Offers observability through CloudWatch metrics and logs

### Application lifecycle: 

You package your Spring AI application as a container image and push it to Amazon ECR. Amazon Bedrock AgentCore creates a Runtime that pulls your image and runs it on managed infrastructure. The Runtime connects to your VPC for database access and exposes an HTTPS endpoint for invocations.

### Key concepts:

- A [Runtime](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/agents-tools-runtime.html) is a managed compute environment that runs your agent container
- [VPC mode](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/agentcore-vpc.html) enables the Runtime to access resources in your VPC (databases, internal services)
- [Custom JWT Authorizer](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/runtime-header-allowlist.html) validates tokens from identity providers like Amazon Cognito

## Introduction to Spring AI AgentCore starter

[Spring AI Bedrock AgentCore](https://github.com/spring-ai-community/spring-ai-bedrock-agentcore) is an AWS-initiated, community-maintained Spring Boot starter that enables existing Spring Boot applications to conform to the Amazon Bedrock AgentCore Runtime contract with minimal configuration.

The starter provides:

- Auto-configuration for AgentCore endpoints when added as dependency
- Simple @AgentCoreInvocation annotation to mark agent methods
- Server-Sent Events (SSE) streaming support with Flux<String> return types
- Built-in /ping endpoint with Spring Boot Actuator integration
- Background task tracking for long-running operations
- Built-in rate limiting for invocations and ping endpoints

Without this starter, you would need to manually implement Amazon Bedrock AgentCore's container protocol, handle WebSocket connections, and parse authentication headers. The starter abstracts these complexities, allowing you to focus on your agent's business logic.
Adding dependencies

## Add the Spring AI AgentCore starter dependency to pom.xml:

```
sed -i '0,/<dependencies>/{/<dependencies>/a\
        <!-- AgentCore dependencies -->\
        <dependency>\
            <groupId>org.springaicommunity</groupId>\
            <artifactId>spring-ai-bedrock-agentcore-starter</artifactId>\
            <version>1.0.0-RC5</version>\
        </dependency>
}' ~/environment/aiagent/pom.xml
```

- 5: Spring AI AgentCore starter for serverless deployment

## Creating the invocation service

Amazon Bedrock AgentCore uses a different invocation mechanism than REST controllers. Create `InvocationService.java`:

```
cat <<'EOF' > ~/environment/aiagent/src/main/java/com/example/agent/InvocationService.java
package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.springaicommunity.agentcore.context.AgentCoreHeaders;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.context.AgentCoreContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class InvocationService {
    private final ChatService chatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InvocationService(ChatService chatService) {
        this.chatService = chatService;
    }

    @AgentCoreInvocation
    public Flux<String> handleInvocation(InvocationRequest request, AgentCoreContext context) throws Exception {
        String jwt = context.getHeader(AgentCoreHeaders.AUTHORIZATION).replace("Bearer ", "");
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
        JsonNode claims = objectMapper.readTree(payload);
        String visitorId = claims.get("sub").asText().replace("-", "").substring(0, 25);
        String authTime = claims.get("auth_time").asText();
        String sessionId = visitorId + ":" + authTime;
        return chatService.chat(request.prompt(), sessionId);
    }
}
EOF
```

- 22: @AgentCoreInvocation marks this method as the Amazon Bedrock AgentCore entry point

## Building the container image

Create a multi-stage Dockerfile that builds the application without static files:

```
cat <<'EOF' > ~/environment/aiagent/Dockerfile
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
EOF
```

- 2: Maven with Amazon Corretto 25 as the build image
- 7: Remove static files (UI deployed separately)
- 8: Build the application inside the container
- 14-15: Create dedicated user for least privilege
- 17: Copy only the JAR from the builder stage

1. Log in to the container registry:

```
ECR_URI="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/aiagent"

aws ecr get-login-password --region ${AWS_REGION} --no-cli-pager | \
  docker login --username AWS --password-stdin ${ECR_URI}
```

2. Set up Docker buildx for cross-platform builds:

```
docker run --privileged --rm tonistiigi/binfmt --install arm64
docker buildx create --name arm64builder --use || docker buildx use arm64builder
docker buildx inspect --bootstrap
```

3. Build and push the Docker image (ARM64 required by Amazon Bedrock AgentCore):

```
cd ~/environment/aiagent
docker buildx build --platform linux/arm64 -t ${ECR_URI}:agentcore --push .
```

## Deploying the application

Amazon Bedrock AgentCore VPC mode requires subnets in [supported Availability Zones](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/agentcore-vpc.html). For us-east-1, the supported AZ IDs are: use1-az1, use1-az2, use1-az4.

1. Get the VPC and network resources:

```
VPC_ID=$(aws ec2 describe-vpcs \
  --filters "Name=tag:Name,Values=workshop-vpc" \
  --query 'Vpcs[0].VpcId' --output text --no-cli-pager)

SUBNET_IDS=$(aws ec2 describe-subnets \
  --filters "Name=vpc-id,Values=${VPC_ID}" \
            "Name=tag:aws-cdk:subnet-type,Values=Private" \
            "Name=availability-zone-id,Values=use1-az1,use1-az2,use1-az4" \
  --query 'Subnets[*].SubnetId' --output json --no-cli-pager)

SG_ID=$(aws ec2 describe-security-groups \
  --filters "Name=group-name,Values=workshop-db-sg" \
  --query 'SecurityGroups[0].GroupId' --output text --no-cli-pager)

echo "VPC: ${VPC_ID}"
echo "Subnets: ${SUBNET_IDS}"
echo "Security Group: ${SG_ID}"
```

- 1-3: Find VPC by name tag workshop-vpc
- 5-9: Filter private subnets by supported AZ IDs for Amazon Bedrock AgentCore VPC mode
- 11-13: Find security group workshop-db-sg that allows database access

Expected output:

```
VPC: vpc-0abc123...
Subnets: ["subnet-0abc123...", "subnet-0def456..."]
Security Group: sg-0abc123...
```

2. Get database credentials and MCP Server URL:

```
DB_URL=$(aws ssm get-parameter --name workshop-db-connection-string --no-cli-pager \
  | jq -r '.Parameter.Value')
DB_USER=$(aws secretsmanager get-secret-value --secret-id workshop-db-secret --no-cli-pager \
  | jq -r '.SecretString' | jq -r .username)
DB_PASS=$(aws secretsmanager get-secret-value --secret-id workshop-db-secret --no-cli-pager \
  | jq -r '.SecretString' | jq -r .password)

MCP_URL=http://$(kubectl get ingress mcpserver -n mcpserver \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

echo "DB URL: ${DB_URL}"
echo "MCP URL: ${MCP_URL}"
```

- 1-2: Database connection string from SSM Parameter Store
- 3-6: Database username and password from Secrets Manager
- 8-9: MCP Server URL from Kubernetes ingress

3. Create the Amazon Bedrock AgentCore Runtime with VPC mode and Cognito authentication:

```
ECR_URI="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/aiagent"

USER_POOL_ID=$(aws cognito-idp list-user-pools --max-results 60 --no-cli-pager \
  --query "UserPools[?Name=='aiagent-user-pool'].Id | [0]" --output text)
CLIENT_ID=$(aws cognito-idp list-user-pool-clients --user-pool-id "${USER_POOL_ID}" --no-cli-pager \
  --query "UserPoolClients[?ClientName=='aiagent-client'].ClientId | [0]" --output text)
COGNITO_DISCOVERY="https://cognito-idp.${AWS_REGION}.amazonaws.com/${USER_POOL_ID}/.well-known/openid-configuration"

ENV_VARS=$(jq -n \
  --arg db_url "${DB_URL}" \
  --arg db_user "${DB_USER}" \
  --arg db_pass "${DB_PASS}" \
  --arg mcp_url "${MCP_URL}" \
  '{SPRING_DATASOURCE_URL: $db_url, SPRING_DATASOURCE_USERNAME: $db_user, SPRING_DATASOURCE_PASSWORD: $db_pass, SPRING_AI_MCP_CLIENT_STREAMABLEHTTP_CONNECTIONS_SERVER1_URL: $mcp_url}')

RUNTIME_RESPONSE=$(aws bedrock-agentcore-control create-agent-runtime \
  --agent-runtime-name aiagent \
  --role-arn "arn:aws:iam::${ACCOUNT_ID}:role/aiagent-agentcore-runtime-role" \
  --agent-runtime-artifact "{\"containerConfiguration\":{\"containerUri\":\"${ECR_URI}:agentcore\"}}" \
  --network-configuration "{\"networkMode\":\"VPC\",\"networkModeConfig\":{\"subnets\":${SUBNET_IDS},\"securityGroups\":[\"${SG_ID}\"]}}" \
  --authorizer-configuration "{\"customJWTAuthorizer\":{\"discoveryUrl\":\"${COGNITO_DISCOVERY}\",\"allowedClients\":[\"${CLIENT_ID}\"]}}" \
  --request-header-configuration '{"requestHeaderAllowlist":["Authorization"]}' \
  --environment-variables "${ENV_VARS}" \
  --region ${AWS_REGION} \
  --no-cli-pager)

RUNTIME_ID=$(echo "${RUNTIME_RESPONSE}" | jq -r '.agentRuntimeId')
echo "Runtime ID: ${RUNTIME_ID}"
```

- 7: Cognito OIDC discovery URL for JWT validation
- 14: Environment variables with database and MCP Server configuration
- 22-23: Header allowlist passes Authorization to container, environment variables configure the application

4. Wait for the runtime to be ready (3-5 minutes):

```
while true; do
  STATUS=$(aws bedrock-agentcore-control get-agent-runtime \
    --agent-runtime-id "${RUNTIME_ID}" \
    --region ${AWS_REGION} \
    --query 'status' --output text --no-cli-pager)
  echo "Status: ${STATUS}"
  if [ "${STATUS}" = "READY" ]; then break; fi
  if [ "${STATUS}" = "FAILED" ]; then echo "Runtime failed"; exit 1; fi
  sleep 15
done
```

## Testing the AI Agent

1. Get the Amazon Bedrock AgentCore endpoint and obtain a Cognito token:

```
RUNTIME_ID=$(aws bedrock-agentcore-control list-agent-runtimes --region ${AWS_REGION} --no-cli-pager \
  --query "agentRuntimes[?agentRuntimeName=='aiagent'].agentRuntimeId | [0]" --output text)
RUNTIME_ARN="arn:aws:bedrock-agentcore:${AWS_REGION}:${ACCOUNT_ID}:runtime/${RUNTIME_ID}"
RUNTIME_ARN_ENCODED=$(echo -n "${RUNTIME_ARN}" | jq -sRr @uri)
API_ENDPOINT="https://bedrock-agentcore.${AWS_REGION}.amazonaws.com/runtimes/${RUNTIME_ARN_ENCODED}/invocations?qualifier=DEFAULT"

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
  --query 'AuthenticationResult.AccessToken' --output text)
```

- 1-5: Build the AgentCore invocation endpoint URL with encoded runtime ARN
- 12-18: Authenticate with Cognito and retrieve access token

2. Test the Amazon Bedrock AgentCore endpoint:

```
curl -N -X POST "${API_ENDPOINT}" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${TOKEN}" \
  -d '{"prompt": "List all unicorns"}' | sed 's/^data://g' | tr -d '\n'; echo
```

```
  % Total    % Received % Xferd  Average Speed   Time    Time     Time  Current
                                 Dload  Upload   Total   Spent    Left  Speed
100   959    0   928  100    31    130      4  0:00:07  0:00:07 --:--:--   226
Here's the complete list of all unicorns currently available at Unicorn Rentals:1. **unicorn-classic-small**   - ID: 88e19edc-9b4f-4676-bd76-2f6645ccf589   - Age: 10 years   - Size: Small   - Type: Classic2. **Spring**   - Age: 20 years   - Size: Small   - Type: SUPER FAST3. **rainbow**   - ID: 394323c7-efbf-4294-a37f-e2d0461e5395   - Age: 5 years   - Size: Medium   - Type: ClassicWe have 3 unicorns total in our rental inventory. Would you like more details about any of these unicorns or help with making a rental selection?
```

## Accessing the logs

View application logs in [Amazon CloudWatch Logs](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/WhatIsCloudWatchLogs.html):

```
RUNTIME_ID=$(aws bedrock-agentcore-control list-agent-runtimes --region ${AWS_REGION} --no-cli-pager \
  --query "agentRuntimes[?agentRuntimeName=='aiagent'].agentRuntimeId | [0]" --output text)
LOG_GROUP_NAME="/aws/bedrock-agentcore/runtimes/${RUNTIME_ID}-DEFAULT"

LOG_STREAM=$(aws logs describe-log-streams --log-group-name ${LOG_GROUP_NAME} \
  --order-by LastEventTime --descending --limit 1 \
  --query 'logStreams[0].logStreamName' --output text --no-cli-pager)
aws logs get-log-events --log-group-name ${LOG_GROUP_NAME} \
  --log-stream-name ${LOG_STREAM} --limit 50 --no-cli-pager \
  --query 'events[].[timestamp, message]' --output text
```

-- 1-3: Build log group name from runtime ID
-- 5-8: Get the most recent log stream and fetch last 50 events

### Deploying the UI (optional)

To use the chat UI instead of curl, deploy it to Amazon S3 and Amazon CloudFront.

1. Create an [S3 bucket](https://docs.aws.amazon.com/AmazonS3/latest/userguide/creating-bucket.html) for static hosting:

```
UI_BUCKET="aiagent-ui-${ACCOUNT_ID}-$(date +%s)"

if [ "${AWS_REGION}" = "us-east-1" ]; then
  aws s3api create-bucket --bucket "${UI_BUCKET}" --no-cli-pager
else
  aws s3api create-bucket --bucket "${UI_BUCKET}" \
    --create-bucket-configuration LocationConstraint="${AWS_REGION}" --no-cli-pager
fi
```

- 1: Generate unique bucket name with account ID and timestamp
- 3-4: us-east-1 doesn't require LocationConstraint
- 6-7: Other Regions require explicit LocationConstraint

2. Create a [CloudFront Origin Access Identity (OAI)](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-s3.html) 
    to securely access S3:

```
OAI_RESPONSE=$(aws cloudfront create-cloud-front-origin-access-identity \
  --cloud-front-origin-access-identity-config \
    "{\"CallerReference\":\"aiagent-$(date +%s)\",\"Comment\":\"OAI for aiagent UI\"}" \
  --no-cli-pager)
OAI_ID=$(echo "${OAI_RESPONSE}" | jq -r '.CloudFrontOriginAccessIdentity.Id')
OAI_CANONICAL=$(aws cloudfront get-cloud-front-origin-access-identity --id "${OAI_ID}" \
  --no-cli-pager --query 'CloudFrontOriginAccessIdentity.S3CanonicalUserId' --output text)
```

- 1-3: Create OAI with unique caller reference
- 5-6: Extract OAI ID and canonical user ID for bucket policy

3. Update the S3 bucket policy to allow CloudFront access:

```
aws s3api put-bucket-policy --bucket "${UI_BUCKET}" --policy "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [{
    \"Effect\": \"Allow\",
    \"Principal\": {\"CanonicalUser\": \"${OAI_CANONICAL}\"},
    \"Action\": \"s3:GetObject\",
    \"Resource\": \"arn:aws:s3:::${UI_BUCKET}/*\"
  }]
}" --no-cli-pager
```

4. Create the [CloudFront distribution](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/distribution-working-with.html) for HTTPS delivery:

```
CF_RESPONSE=$(aws cloudfront create-distribution \
  --distribution-config "{
    \"CallerReference\": \"aiagent-$(date +%s)\",
    \"Comment\": \"aiagent UI\",
    \"Enabled\": true,
    \"DefaultRootObject\": \"index.html\",
    \"Origins\": {
      \"Quantity\": 1,
      \"Items\": [{
        \"Id\": \"S3-${UI_BUCKET}\",
        \"DomainName\": \"${UI_BUCKET}.s3.${AWS_REGION}.amazonaws.com\",
        \"S3OriginConfig\": {
          \"OriginAccessIdentity\": \"origin-access-identity/cloudfront/${OAI_ID}\"
        }
      }]
    },
    \"DefaultCacheBehavior\": {
      \"TargetOriginId\": \"S3-${UI_BUCKET}\",
      \"ViewerProtocolPolicy\": \"redirect-to-https\",
      \"AllowedMethods\": {
        \"Quantity\": 2,
        \"Items\": [\"GET\", \"HEAD\"],
        \"CachedMethods\": {\"Quantity\": 2, \"Items\": [\"GET\", \"HEAD\"]}
      },
      \"ForwardedValues\": {\"QueryString\": false, \"Cookies\": {\"Forward\": \"none\"}},
      \"MinTTL\": 0,
      \"DefaultTTL\": 86400,
      \"MaxTTL\": 31536000,
      \"Compress\": true
    },
    \"CustomErrorResponses\": {
      \"Quantity\": 1,
      \"Items\": [{
        \"ErrorCode\": 403,
        \"ResponsePagePath\": \"/index.html\",
        \"ResponseCode\": \"200\",
        \"ErrorCachingMinTTL\": 300
      }]
    },
    \"PriceClass\": \"PriceClass_100\"
  }" \
  --no-cli-pager)

CF_DIST_ID=$(echo "${CF_RESPONSE}" | jq -r '.Distribution.Id')
CF_DOMAIN=$(echo "${CF_RESPONSE}" | jq -r '.Distribution.DomainName')
```

- 10: Configure S3 bucket as origin
- 13: Use OAI for secure S3 access
- 19: Redirect HTTP to HTTPS

5. Generate the UI configuration and upload files to S3:

```
RUNTIME_ID=$(aws bedrock-agentcore-control list-agent-runtimes --region ${AWS_REGION} --no-cli-pager \
  --query "agentRuntimes[?agentRuntimeName=='aiagent'].agentRuntimeId | [0]" --output text)
RUNTIME_ARN="arn:aws:bedrock-agentcore:${AWS_REGION}:${ACCOUNT_ID}:runtime/${RUNTIME_ID}"
RUNTIME_ARN_ENCODED=$(echo -n "${RUNTIME_ARN}" | jq -sRr @uri)
API_ENDPOINT="https://bedrock-agentcore.${AWS_REGION}.amazonaws.com/runtimes/${RUNTIME_ARN_ENCODED}/invocations?qualifier=DEFAULT"

USER_POOL_ID=$(aws cognito-idp list-user-pools --max-results 60 --no-cli-pager \
  --query "UserPools[?Name=='aiagent-user-pool'].Id | [0]" --output text)
CLIENT_ID=$(aws cognito-idp list-user-pool-clients --user-pool-id "${USER_POOL_ID}" --no-cli-pager \
  --query "UserPoolClients[?ClientName=='aiagent-client'].ClientId | [0]" --output text)

cat > ~/environment/aiagent/src/main/resources/static/config.json << EOF
{
  "userPoolId": "${USER_POOL_ID}",
  "clientId": "${CLIENT_ID}",
  "region": "${AWS_REGION}",
  "apiEndpoint": "${API_ENDPOINT}"
}
EOF

CF_DIST_ID=$(aws cloudfront list-distributions --no-cli-pager \
  --query "DistributionList.Items[?Comment=='aiagent UI'].Id | [0]" --output text)

UI_DIR=~/environment/aiagent/src/main/resources/static
for file in ${UI_DIR}/*.html ${UI_DIR}/*.js ${UI_DIR}/*.css ${UI_DIR}/*.json ${UI_DIR}/*.svg; do
  if [ -f "${file}" ]; then
    filename=$(basename "${file}")
    case "${filename}" in
      *.html) CONTENT_TYPE="text/html" ;;
      *.js) CONTENT_TYPE="application/javascript" ;;
      *.css) CONTENT_TYPE="text/css" ;;
      *.json) CONTENT_TYPE="application/json" ;;
      *.svg) CONTENT_TYPE="image/svg+xml" ;;
    esac
    aws s3 cp "${file}" "s3://${UI_BUCKET}/${filename}" \
      --content-type "${CONTENT_TYPE}" --no-cli-pager
  fi
done

aws cloudfront create-invalidation \
  --distribution-id "${CF_DIST_ID}" \
  --paths "/*" \
  --no-cli-pager

CF_DOMAIN=$(aws cloudfront get-distribution --id "${CF_DIST_ID}" --no-cli-pager \
  --query 'Distribution.DomainName' --output text)
echo "UI URL: https://${CF_DOMAIN}"
```

- 14-17: Create config.json with Cognito and AgentCore endpoint settings
- 40-43: Invalidate CloudFront cache to serve new files

6. Wait for CloudFront to become available:

```
while true; do
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "https://${CF_DOMAIN}" || echo "000")
  echo "CloudFront HTTP status: ${HTTP_STATUS}"
  if [ "${HTTP_STATUS}" = "200" ]; then break; fi
  sleep 15
done
```

7. Open the UI URL and log in with alice or bob:

```
echo ${IDE_PASSWORD}
```

### Redeploy after changes

Use this workflow to update the runtime after making code changes to your application.

```
cd ~/environment/aiagent

ECR_URI="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/aiagent"

# Build and push
docker buildx build --platform linux/arm64 -t ${ECR_URI}:agentcore --push .

# Get runtime ID
RUNTIME_ID=$(aws bedrock-agentcore-control list-agent-runtimes --region ${AWS_REGION} --no-cli-pager \
  --query "agentRuntimes[?agentRuntimeName=='aiagent'].agentRuntimeId | [0]" --output text)

# Get current config (preserve everything)
CURRENT=$(aws bedrock-agentcore-control get-agent-runtime --agent-runtime-id ${RUNTIME_ID} --region ${AWS_REGION} --no-cli-pager)
ROLE_ARN=$(echo $CURRENT | jq -r '.roleArn')
NETWORK_CONFIG=$(echo $CURRENT | jq -c '.networkConfiguration')
AUTH_CONFIG=$(echo $CURRENT | jq -c '.authorizerConfiguration')
ENV_VARS=$(echo $CURRENT | jq -c '.environmentVariables // {}')

# Update with all existing config preserved
aws bedrock-agentcore-control update-agent-runtime \
  --agent-runtime-id ${RUNTIME_ID} \
  --role-arn "${ROLE_ARN}" \
  --network-configuration "${NETWORK_CONFIG}" \
  --authorizer-configuration "${AUTH_CONFIG}" \
  --request-header-configuration '{"requestHeaderAllowlist":["Authorization"]}' \
  --environment-variables "${ENV_VARS}" \
  --agent-runtime-artifact "{\"containerConfiguration\":{\"containerUri\":\"${ECR_URI}:agentcore\"}}" \
  --region ${AWS_REGION} \
  --no-cli-pager

# Wait for ready
while true; do
  STATUS=$(aws bedrock-agentcore-control get-agent-runtime --agent-runtime-id "${RUNTIME_ID}" --region ${AWS_REGION} --query 'status' --output text --no-cli-pager)
  echo "Status: ${STATUS}"
  if [ "${STATUS}" = "READY" ]; then break; fi
  if [ "${STATUS}" = "FAILED" ]; then echo "FAILED"; exit 1; fi
  sleep 10
done
```

### Cleanup for other deployment options



## In this section you have learned how to:

- Add Amazon Bedrock AgentCore dependencies and create an InvocationService
- Build a multi-stage Docker image with Amazon Corretto 25
- Deploy to Amazon Bedrock AgentCore with VPC mode for database access
- Configure Amazon Cognito JWT authentication for the runtime
- Test the deployed agent with curl
