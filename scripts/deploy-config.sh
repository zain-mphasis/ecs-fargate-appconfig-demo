#!/usr/bin/env bash
# Publishes config/runtime-settings.json as a new AWS AppConfig hosted version and deploys it.
# Used by the Jenkins pipeline so a git push flows all the way through to the live page.
set -euo pipefail

REGION=us-east-1
STACK=DemoAppConfigStack

out() { aws cloudformation describe-stacks --stack-name "$STACK" --region "$REGION" \
          --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text; }

APP_ID=$(out ApplicationId)
ENV_ID=$(out EnvironmentId)
PROFILE_ID=$(out ConfigurationProfileId)
STRATEGY_ID=$(aws appconfig list-deployment-strategies --region "$REGION" \
                --query "Items[?Name=='ecs-fargate-demo-all-at-once'].Id" --output text)

echo "AppConfig: application=$APP_ID environment=$ENV_ID profile=$PROFILE_ID strategy=$STRATEGY_ID"

VERSION=$(aws appconfig create-hosted-configuration-version --region "$REGION" \
            --application-id "$APP_ID" --configuration-profile-id "$PROFILE_ID" \
            --content-type application/json \
            --content fileb://config/runtime-settings.json \
            appconfig-out.json --query VersionNumber --output text)
echo "Created hosted configuration version $VERSION"

DEP=$(aws appconfig start-deployment --region "$REGION" \
        --application-id "$APP_ID" --environment-id "$ENV_ID" \
        --configuration-profile-id "$PROFILE_ID" --configuration-version "$VERSION" \
        --deployment-strategy-id "$STRATEGY_ID" --query DeploymentNumber --output text)
echo "Started deployment $DEP"

for _ in $(seq 1 24); do
  STATE=$(aws appconfig get-deployment --region "$REGION" \
            --application-id "$APP_ID" --environment-id "$ENV_ID" \
            --deployment-number "$DEP" --query State --output text)
  echo "Deployment state: $STATE"
  [ "$STATE" = "COMPLETE" ] && exit 0
  [ "$STATE" = "ROLLED_BACK" ] && { echo "Deployment rolled back" >&2; exit 1; }
  sleep 5
done
echo "Deployment did not complete in time" >&2
exit 1
