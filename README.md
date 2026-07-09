# ECS Fargate + AWS AppConfig Demo

[![CI](https://github.com/zain-mphasis/ecs-fargate-appconfig-demo/actions/workflows/ci.yml/badge.svg)](https://github.com/zain-mphasis/ecs-fargate-appconfig-demo/actions/workflows/ci.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=zain-mphasis_ecs-fargate-appconfig-demo&metric=alert_status)](https://sonarcloud.io/dashboard?id=zain-mphasis_ecs-fargate-appconfig-demo)

A Spring Boot application deployed to an **ECS Fargate cluster** that serves a configuration
page whose values are read **dynamically from AWS AppConfig**. Everything — application *and*
infrastructure — is written in **Java**: the infrastructure is AWS CDK (Java), so the whole
solution is Everything-as-Code, built and deployed through a **Jenkins pipeline** with a
**100% test-coverage gate (JaCoCo)** and **SonarQube quality-gate validation**.

## Architecture

```
                        ┌────────────────────────────────────────────────┐
                        │                  AWS Account                   │
   Jenkins pipeline     │  ┌──────────┐      ┌───────────────────────┐   │
  ┌──────────────────┐  │  │   ECR    │      │      VPC (2 AZs)      │   │
  │ mvn verify       │  │  │  repo    │      │  ┌─────────────────┐  │   │
  │  (100% coverage) │  │  └────▲─────┘      │  │  ECS Fargate    │  │   │
  │ sonar + gate     │──┼──────►│ push       │  │  cluster        │  │   │
  │ jib image push   │  │       │ pull       │  │  ┌───────────┐  │  │   │
  │ cdk deploy       │  │       └────────────┼──┼─►│ Spring    │  │  │   │
  └──────────────────┘  │                    │  │  │ Boot task │◄─┼──┼───┼── ALB ◄── Browser
                        │  ┌──────────────┐  │  │  │ x2        │  │  │   │
                        │  │ AWS AppConfig│◄─┼──┼──┤ poll 30s  │  │  │   │
                        │  │ app/env/     │  │  │  └───────────┘  │  │   │
                        │  │ profile      │  │  └─────────────────┘  │   │
                        │  └──────────────┘  └───────────────────────┘   │
                        └────────────────────────────────────────────────┘
```

The app polls the AppConfig Data API (`StartConfigurationSession` / `GetLatestConfiguration`)
every 30 seconds; the browser page polls `/api/config` every 5 seconds. Deploying a new
configuration version in AppConfig is reflected on the page within ~30 seconds — no restart.

## Repository layout

| Path | Description |
|---|---|
| [app/](app/) | Spring Boot application (config page + AppConfig polling) |
| [app/Dockerfile](app/Dockerfile) | Container image for the app |
| [infra/](infra/) | AWS CDK **in Java**: ECR, AppConfig and Fargate service stacks |
| [Jenkinsfile](Jenkinsfile) | Full CI/CD pipeline: test → coverage gate → Sonar → deploy |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | GitHub Actions CI (runs on every push): same build, coverage gate and Sonar quality gate as the Jenkins pipeline |
| [pom.xml](pom.xml) | Parent POM with the JaCoCo 100% rule and Sonar settings |

### CDK stacks (all Java)

| Stack | Contents |
|---|---|
| `DemoEcrStack` | ECR repository (scan-on-push, keep last 10 images) |
| `DemoAppConfigStack` | AppConfig application, environment, hosted freeform profile, all-at-once deployment strategy, initial configuration version + deployment |
| `DemoFargateServiceStack` | VPC (2 AZs), ECS Fargate cluster, ALB-fronted service (2 tasks), task-role IAM permissions for AppConfig, health check on `/actuator/health` |

## Prerequisites

- JDK 17+, Maven 3.9+
- No Docker required — the image is built and pushed by the Jib Maven plugin
  (a [Dockerfile](app/Dockerfile) is still included for anyone who prefers a classic docker build)
- Node.js + AWS CDK CLI (`npm install -g aws-cdk`) — the CDK *code* is Java; the CLI is the only Node piece
- AWS CLI configured, account bootstrapped once: `cdk bootstrap aws://<account>/<region>`
- Jenkins with: Pipeline, JUnit, HTML Publisher, SonarQube Scanner, AWS Steps plugins; a SonarQube server registered as `sonarqube`; Maven/JDK tools named `maven-3.9` / `jdk-17`; AWS credentials with id `aws-deploy-credentials`

## Build and test locally

```bash
mvn clean verify          # runs all tests; FAILS if line or branch coverage < 100%
```

Coverage reports: `app/target/site/jacoco/index.html` and `infra/target/site/jacoco/index.html`.

Run the app locally (no AWS needed — serves values from `local-config.json`):

```bash
mvn -pl app spring-boot:run
# open http://localhost:8080
```

## Deploy (what the Jenkins pipeline does)

```bash
# 1. Platform stacks: ECR + AppConfig
cd infra
npx cdk deploy DemoEcrStack DemoAppConfigStack --require-approval never

# 2. Build and push the image
cd ..
mvn -pl app -am clean verify
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_URI="${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com/ecs-fargate-appconfig-demo"
mvn -pl app jib:build \
  -Djib.to.image="${ECR_URI}:v1" \
  -Djib.to.auth.username=AWS \
  -Djib.to.auth.password="$(aws ecr get-login-password)"

# 3. Fargate service
cd infra
npx cdk deploy DemoFargateServiceStack -c imageTag=v1 --require-approval never
```

The service URL is printed as a stack output (`ServiceURL`). Open it in a browser — the page
shows the configuration served from AWS AppConfig with source badge **AWS AppConfig**.

## Demo: change a value in AppConfig and watch the page update

```bash
APP_ID=$(aws cloudformation describe-stacks --stack-name DemoAppConfigStack \
  --query "Stacks[0].Outputs[?OutputKey=='ApplicationId'].OutputValue" --output text)
ENV_ID=$(aws cloudformation describe-stacks --stack-name DemoAppConfigStack \
  --query "Stacks[0].Outputs[?OutputKey=='EnvironmentId'].OutputValue" --output text)
PROFILE_ID=$(aws cloudformation describe-stacks --stack-name DemoAppConfigStack \
  --query "Stacks[0].Outputs[?OutputKey=='ConfigurationProfileId'].OutputValue" --output text)
STRATEGY_ID=$(aws appconfig list-deployment-strategies \
  --query "Items[?Name=='ecs-fargate-demo-all-at-once'].Id" --output text)

# 1. Create a new configuration version with an updated value
cat > /tmp/new-config.json <<'EOF'
{
  "applicationName": "ECS Fargate AppConfig Demo",
  "environment": "production",
  "welcomeMessage": "Updated live from AWS AppConfig!",
  "featureDarkModeEnabled": false,
  "maxItemsPerPage": 100,
  "supportContact": "zain.akhtar1@mphasis.com"
}
EOF
VERSION=$(aws appconfig create-hosted-configuration-version \
  --application-id "$APP_ID" --configuration-profile-id "$PROFILE_ID" \
  --content-type application/json --content fileb:///tmp/new-config.json \
  /tmp/out.json --query VersionNumber --output text)

# 2. Deploy it
aws appconfig start-deployment \
  --application-id "$APP_ID" --environment-id "$ENV_ID" \
  --configuration-profile-id "$PROFILE_ID" \
  --configuration-version "$VERSION" \
  --deployment-strategy-id "$STRATEGY_ID"
```

Within one poll interval (≤30s) the running tasks pick up the new version and the page —
which itself refreshes every 5 seconds — shows the new values. No redeploy, no restart.

(You can do the same from the AWS console: AppConfig → ecs-fargate-appconfig-demo →
runtime-settings → *Create version* → *Start deployment*.)

## Quality gates

- **100% coverage, enforced**: the parent POM binds `jacoco:check` to `verify` with
  `LINE = 1.00` and `BRANCH = 1.00` on both modules. Any uncovered line or branch fails the build.
- **SonarQube**: `mvn sonar:sonar` runs inside `withSonarQubeEnv`, and the pipeline blocks on
  `waitForQualityGate` — a red quality gate aborts the deployment. Coverage is imported from
  the JaCoCo XML reports.

## Configuration reference

| Environment variable | Purpose | Set by |
|---|---|---|
| `SPRING_PROFILES_ACTIVE=aws` | Switches from local file to AppConfig | Fargate task definition (CDK) |
| `APPCONFIG_APPLICATION_ID` | AppConfig application to read | CDK cross-stack reference |
| `APPCONFIG_ENVIRONMENT_ID` | AppConfig environment | CDK cross-stack reference |
| `APPCONFIG_PROFILE_ID` | Configuration profile | CDK cross-stack reference |
| `APPCONFIG_POLL_INTERVAL_SECONDS` | Poll frequency (default 30) | CDK / optional |
| `AWS_REGION` | SDK region | CDK |
