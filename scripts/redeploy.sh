#!/usr/bin/env bash
# One-command rebuild of the whole AWS environment on WHATEVER account the
# current AWS credentials point at. Run after `aws configure` with a new account.
#
#   bash scripts/redeploy.sh
#
# It bootstraps CDK, deploys ECR + AppConfig + Fargate, builds & pushes the image,
# creates the AgentAudit table, and recreates the GitHub OIDC deploy role.
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Zulu/zulu-25}"
export PATH="$JAVA_HOME/bin:/c/Program Files/apache-maven-3.9.10/bin:$PATH"
REGION="us-east-1"
REPO="ecs-fargate-appconfig-demo"
GH_REPO="zain-mphasis/ecs-fargate-appconfig-demo"
CDK='node C:\Users\zain.akhtar1\AppData\Roaming\npm\node_modules\aws-cdk\bin\cdk'

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
echo ">>> Target AWS account: $ACCOUNT (region $REGION)"

echo ">>> 1/6 CDK bootstrap"
( cd infra && eval "$CDK bootstrap aws://$ACCOUNT/$REGION" )

echo ">>> 2/6 Deploy ECR + AppConfig stacks"
( cd infra && eval "$CDK deploy DemoEcrStack DemoAppConfigStack --require-approval never" )

echo ">>> 3/6 Build & push image (Jib)"
mvn -B -q -pl app -am package -DskipTests
mvn -B -pl app jib:build \
  -Djib.to.image="$ACCOUNT.dkr.ecr.$REGION.amazonaws.com/$REPO:v1" \
  -Djib.to.tags=latest \
  -Djib.to.auth.username=AWS \
  -Djib.to.auth.password="$(aws ecr get-login-password --region $REGION)"

echo ">>> 4/6 Deploy Fargate service"
( cd infra && eval "$CDK deploy DemoFargateServiceStack -c imageTag=v1 --require-approval never" )

echo ">>> 5/6 Create AgentAudit DynamoDB table (for the self-healing agent)"
aws dynamodb create-table --table-name AgentAudit \
  --attribute-definitions AttributeName=auditId,AttributeType=S \
  --key-schema AttributeName=auditId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST --region $REGION 2>/dev/null \
  && aws dynamodb wait table-exists --table-name AgentAudit --region $REGION \
  || echo "    (AgentAudit already exists — skipping)"

echo ">>> 6/6 Recreate GitHub OIDC provider + deploy role"
aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 2>/dev/null || echo "    (OIDC provider already exists)"
cat > /tmp/trust.json <<EOF
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Federated":"arn:aws:iam::$ACCOUNT:oidc-provider/token.actions.githubusercontent.com"},"Action":"sts:AssumeRoleWithWebIdentity","Condition":{"StringEquals":{"token.actions.githubusercontent.com:aud":"sts.amazonaws.com"},"StringLike":{"token.actions.githubusercontent.com:sub":"repo:$GH_REPO:ref:refs/heads/main"}}}]}
EOF
aws iam create-role --role-name github-actions-deploy \
  --assume-role-policy-document file:///tmp/trust.json 2>/dev/null || echo "    (role already exists)"
aws iam attach-role-policy --role-name github-actions-deploy \
  --policy-arn arn:aws:iam::aws:policy/PowerUserAccess 2>/dev/null || true

URL=$(aws cloudformation describe-stacks --stack-name DemoFargateServiceStack --region $REGION \
  --query "Stacks[0].Outputs[?contains(OutputKey,'ServiceURL')].OutputValue | [0]" --output text)
echo ""
echo "=================================================================="
echo " DONE. New live page URL:"
echo "   $URL"
echo ""
echo " Next (manual, needs a git push):"
echo "   - Update account id 152174417241 -> $ACCOUNT in .github/workflows/*.yml"
echo "   - Update the ALB URL in the Jenkins 'Show live page' stage"
echo "=================================================================="
