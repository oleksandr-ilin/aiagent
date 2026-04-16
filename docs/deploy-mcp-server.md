# Deploy MCP Server

The MCP Server is deployed to Amazon Elastic Kubernetes Service (Amazon EKS) as a standard container deployment.

> The MCP Server is deployed once and shared by all AI Agent deployments.

Building the container image

[Jib](https://github.com/GoogleContainerTools/jib) builds optimized container images without a Dockerfile. The `mcpserver` application already has this dependency configured.

1. Log in to the container registry:

```
aws ecr get-login-password --region ${AWS_REGION} \
  | docker login --username AWS --password-stdin ${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com
```

2. Build and push the container image:

```
cd ~/environment/mcpserver
mvn compile jib:build \
  -Dimage=${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/mcpserver:latest \
  -DskipTests
```

## Understanding EKS Pod Identity

[Amazon EKS Pod Identity](https://docs.aws.amazon.com/eks/latest/userguide/pod-identities.html) provides a modern way to grant AWS permissions to Pods. 
The workshop deployment creates an IAM role that the application will use.

**Pod Identity Role** (unicornstore-eks-pod-role):

- Assumed by: pods.eks.amazonaws.com
- Permissions:
  - Read access to workshop-db-secret and workshop-db-connection-string
  - EventBridge PutEvents to unicorns event bus
  - CloudWatchAgentServerPolicy managed policy
- Purpose: Runtime permissions for the application to access AWS services

The role is associated with a Kubernetes service account, allowing Pods using that service account to assume the IAM role.

## Setting up the namespace and service account

1. Create the namespace:

```
kubectl create namespace mcpserver
```

2. Create a [service account](https://kubernetes.io/docs/concepts/security/service-accounts/):

```
kubectl create serviceaccount mcpserver -n mcpserver
```

## Configuring EKS Pod Identity

Associate the service account with the IAM role to grant AWS permissions to Pods.

1. Create the Pod Identity association:

```
aws eks create-pod-identity-association \
  --cluster-name workshop-eks \
  --namespace mcpserver \
  --service-account mcpserver \
  --role-arn arn:aws:iam::${ACCOUNT_ID}:role/unicornstore-eks-pod-role \
  --no-cli-pager
```

2. Verify the association:

```
ASSOCIATION_ID=$(aws eks list-pod-identity-associations --cluster-name workshop-eks --no-cli-pager \
  | jq -r '.associations[] | select(.namespace=="mcpserver") | .associationId')
aws eks describe-pod-identity-association \
  --cluster-name workshop-eks \
  --association-id ${ASSOCIATION_ID} \
  --no-cli-pager
```

## Configuring secrets access

The [Secrets Store CSI Driver](https://secrets-store-csi-driver.sigs.k8s.io/) mounts AWS secrets as files in the Pod. 
Spring Boot reads these files using `SPRING_CONFIG_IMPORT`.

1. Create the manifests directory:

```
mkdir -p ~/environment/mcpserver/k8s
```

2. Create and apply the SecretProviderClass:

```
cat <<EOF > ~/environment/mcpserver/k8s/secret-provider-class.yaml
apiVersion: secrets-store.csi.x-k8s.io/v1
kind: SecretProviderClass
metadata:
  name: mcpserver-secrets
  namespace: mcpserver
spec:
  provider: aws
  parameters:
    usePodIdentity: "true"
    objects: |
      - objectName: "workshop-db-secret"
        objectType: "secretsmanager"
        jmesPath:
          - path: "password"
            objectAlias: "spring.datasource.password"
          - path: "username"
            objectAlias: "spring.datasource.username"
      - objectName: "workshop-db-connection-string"
        objectType: "ssmparameter"
        objectAlias: "spring.datasource.url"
EOF
kubectl apply -f ~/environment/mcpserver/k8s/secret-provider-class.yaml
```

- 10: usePodIdentity: "true" enables EKS Pod Identity for authentication
- 12-13: Mount database secret from Secrets Manager
- 19-21: Mount database connection string from Parameter Store

> The `usePodIdentity: "true"` parameter is required to use EKS Pod Identity for authentication instead of IRSA.

## Deploying the application

1. Create and apply the Deployment:

```
ECR_URI=${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/mcpserver
cat <<EOF > ~/environment/mcpserver/k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mcpserver
  namespace: mcpserver
  labels:
    app: mcpserver
spec:
  replicas: 1
  selector:
    matchLabels:
      app: mcpserver
  template:
    metadata:
      labels:
        app: mcpserver
    spec:
      serviceAccountName: mcpserver
      nodeSelector:
        karpenter.sh/nodepool: workshop
      containers:
        - name: mcpserver
          image: ${ECR_URI}:latest
          imagePullPolicy: Always
          ports:
            - containerPort: 8080
          env:
            - name: SPRING_CONFIG_IMPORT
              value: "optional:configtree:/mnt/secrets-store/"
          resources:
            requests:
              cpu: "1"
              memory: "2Gi"
            limits:
              cpu: "1"
              memory: "2Gi"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            failureThreshold: 6
            periodSeconds: 5
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            failureThreshold: 6
            periodSeconds: 5
            initialDelaySeconds: 10
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            failureThreshold: 10
            periodSeconds: 5
            initialDelaySeconds: 20
          volumeMounts:
            - name: secrets-store
              mountPath: "/mnt/secrets-store"
              readOnly: true
          securityContext:
            runAsNonRoot: true
            runAsUser: 1000
            allowPrivilegeEscalation: false
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 10"]
      volumes:
        - name: secrets-store
          csi:
            driver: secrets-store.csi.k8s.io
            readOnly: true
            volumeAttributes:
              secretProviderClass: mcpserver-secrets
EOF
kubectl apply -f ~/environment/mcpserver/k8s/deployment.yaml
```

- 20: serviceAccountName links the Pod to Pod Identity
- 21-22: nodeSelector ensures the Pod runs on the workshop NodePool
- 29-31: SPRING_CONFIG_IMPORT tells Spring Boot to read properties from mounted files
- 59-62: CSI volume mounts secrets as files at /mnt/secrets-store/

2. Create and apply the Service:

```
cat <<EOF > ~/environment/mcpserver/k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: mcpserver
  namespace: mcpserver
  labels:
    app: mcpserver
spec:
  type: ClusterIP
  selector:
    app: mcpserver
  ports:
    - port: 80
      targetPort: 8080
      protocol: TCP
EOF
kubectl apply -f ~/environment/mcpserver/k8s/service.yaml
```

3. Create and apply the Ingress (VPC-internal only):

```
cat <<EOF > ~/environment/mcpserver/k8s/ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: mcpserver
  namespace: mcpserver
  annotations:
    alb.ingress.kubernetes.io/scheme: internal
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/healthcheck-path: /actuator/health
  labels:
    app: mcpserver
spec:
  ingressClassName: alb
  rules:
    - http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: mcpserver
                port:
                  number: 80
EOF
kubectl apply -f ~/environment/mcpserver/k8s/ingress.yaml
```

The `internal` scheme creates a VPC-internal Application Load Balancer. All AI Agent deployments (Amazon EKS, Amazon ECS, Amazon Bedrock AgentCore, AWS Lambda) run in the same VPC and can access this endpoint.

## Testing the MCP Server

1. Wait for the load balancer:

```
MCP_URL=http://$(kubectl get ingress mcpserver -n mcpserver \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

while ! curl -s --max-time 5 ${MCP_URL} > /dev/null 2>&1; do
  echo "Waiting for load balancer..." && sleep 15
done

echo "MCP Server URL: ${MCP_URL}"
```

> The internal load balancer creation takes 2-5 minutes.

2. Test the MCP Server:

```
curl -s ${MCP_URL}; echo
```

Expected output:

```
Welcome to the Unicorn Store!
```

3. Create a unicorn:

```
curl -X POST ${MCP_URL}/unicorns \
  -H "Content-Type: application/json" \
  -d '{"name": "rainbow", "age": "5", "type": "classic", "size": "medium"}'; echo
```

## Accessing the logs

```
kubectl logs -n mcpserver \
  $(kubectl get pods -n mcpserver -o jsonpath='{.items[0].metadata.name}')
```

## Section finished

In this section you have learned how to:

- Deploy the MCP Server to Amazon EKS as a shared service
- Configure VPC-internal access using an internal Application Load Balancer
- Set up EKS Pod Identity for AWS service access
