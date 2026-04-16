# Observability

## Introduction to observability

Spring AI builds upon the observability features in the Spring ecosystem to provide insights into AI-related operations. Spring AI provides metrics and tracing capabilities for its core components: ChatClient (including Advisor), ChatModel, EmbeddingModel, ImageModel, and VectorStore.

### Why observability matters for AI applications:

- Monitor token usage and costs across model invocations
- Debug conversation flows and tool executions
- Track latency and performance metrics
- Ensure compliance with logging requirements

## GenAI observability with Amazon Bedrock

[Amazon Bedrock](https://aws.amazon.com/bedrock/) provides [model invocation logging](https://docs.aws.amazon.com/bedrock/latest/userguide/model-invocation-logging.html)
to monitor and analyze all model invocations in your AWS account. This enables you to collect request data, response data, and metadata for debugging, compliance, and cost analysis.

## Enabling model invocation logging

Run the following commands to enable Bedrock model invocation logging to both CloudWatch Logs and S3:

```
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text --no-cli-pager)
BUCKET_NAME=$(aws ssm get-parameter --name workshop-bucket-name \
  --query 'Parameter.Value' --output text --no-cli-pager)
ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/workshop-bedrock-logging-role"

aws logs create-log-group \
  --log-group-name /aws/bedrock/model-invocations --no-cli-pager 2>/dev/null || true

cat > /tmp/bedrock-logging-config.json << EOF
{
    "loggingConfig": {
        "cloudWatchConfig": {
            "logGroupName": "/aws/bedrock/model-invocations",
            "roleArn": "${ROLE_ARN}",
            "largeDataDeliveryS3Config": {
                "bucketName": "${BUCKET_NAME}",
                "keyPrefix": "bedrock-logs"
            }
        },
        "s3Config": {
            "bucketName": "${BUCKET_NAME}",
            "keyPrefix": "bedrock-logs"
        },
        "textDataDeliveryEnabled": true,
        "imageDataDeliveryEnabled": true,
        "embeddingDataDeliveryEnabled": true
    }
}
EOF

aws bedrock put-model-invocation-logging-configuration \
  --cli-input-json file:///tmp/bedrock-logging-config.json \
  --no-cli-pager
```

- 12-19: [CloudWatch Logs](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/WhatIsCloudWatchLogs.html) configuration with log group, IAM role, and S3 for large payloads
- 20-23: [Amazon S3](https://aws.amazon.com/s3/) configuration for long-term log retention

## Verifying the configuration

Verify the logging configuration in the [Amazon Bedrock Settings](https://console.aws.amazon.com/bedrock/home#/settings) console or using the CLI:

```
aws bedrock get-model-invocation-logging-configuration --no-cli-pager
```

## Viewing logs

After making AI Agent requests, view the logs in:

- CloudWatch Logs: [Log groups](https://console.aws.amazon.com/cloudwatch/home#logsV2:log-groups/log-group/$252Faws$252Fbedrock$252Fmodel-invocations) → `/aws/bedrock/model-invocations`
- S3: Workshop bucket → `bedrock-logs/` prefix

Amazon CloudWatch GenAI Observability provides dashboards for monitoring model usage:

```
Console Home > CloudWatch > GenAi Observability: Model Invocations
```