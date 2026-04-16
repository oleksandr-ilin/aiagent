# Run MCP Server

```
cd ~/environment/aiagent
export SPRING_DATASOURCE_URL=$(aws ssm get-parameter --name workshop-db-connection-string --no-cli-pager \
  | jq --raw-output '.Parameter.Value')
export SPRING_DATASOURCE_USERNAME=$(aws secretsmanager get-secret-value --secret-id workshop-db-secret --no-cli-pager \
  | jq --raw-output '.SecretString' | jq -r .username)
export SPRING_DATASOURCE_PASSWORD=$(aws secretsmanager get-secret-value --secret-id workshop-db-secret --no-cli-pager \
  | jq --raw-output '.SecretString' | jq -r .password)
export SPRING_AI_MCP_CLIENT_STREAMABLEHTTP_CONNECTIONS_SERVER1_URL=http://localhost:8081
./mvnw spring-boot:run
```