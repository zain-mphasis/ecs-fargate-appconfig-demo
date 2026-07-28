# Cloud-Native Java Platform on AWS ECS Fargate

[![CI](https://github.com/zain-mphasis/ecs-fargate-appconfig-demo/actions/workflows/ci.yml/badge.svg)](https://github.com/zain-mphasis/ecs-fargate-appconfig-demo/actions/workflows/ci.yml)
[![DynamoDB CI](https://github.com/zain-mphasis/ecs-fargate-appconfig-demo/actions/workflows/dynamodb-ci.yml/badge.svg)](https://github.com/zain-mphasis/ecs-fargate-appconfig-demo/actions/workflows/dynamodb-ci.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=zain-mphasis_ecs-fargate-appconfig-demo&metric=alert_status)](https://sonarcloud.io/dashboard?id=zain-mphasis_ecs-fargate-appconfig-demo)

A single repository containing two complementary, production-style demonstrations of running
**Java Spring Boot workloads on AWS ECS Fargate** — everything as code, everything deployed
through automated pipelines. They share the same platform (Fargate, ECR, Jenkins, GitHub
Actions) but showcase two different pillars of modern delivery:

| Project | Pillar it demonstrates | One-line summary |
|---|---|---|
| **AppConfig live-config demo** | **Dynamic configuration** | A config page whose values update live from AWS AppConfig in seconds — no rebuild, no restart. |
| **Self-healing DynamoDB demo** | **Autonomous CI/CD & recovery** | An employee API on DynamoDB with agents that detect a bug, auto-fix it, open a PR for approval, deploy, validate, and roll back automatically on failure. |

Together they tell one story: **the fast, safe path (configuration) and the guarded, self-correcting
path (code) — both fully automated, both on ECS Fargate, all in Java.**

## Repository layout

```
.
├── app/              Project 1 — Spring Boot config page (reads AWS AppConfig)
├── infra/            Project 1 — AWS CDK (Java): ECR, AppConfig, Fargate stacks
├── config/           Project 1 — runtime config as code (edit → push → live)
├── Jenkinsfile       Project 1 — build → coverage gate → Sonar → deploy
│
├── dynamodb-demo/    Project 2 — Spring Boot employee API on DynamoDB (H2 locally)
├── agent-runner/     Project 2 — Java self-healing agents (monitor/fix/PR/validate/rollback)
├── agent-config/     Project 2 — agent rules (project-config.json, agent-prompt.md)
│
├── docs/
│   ├── appconfig-demo.md        Full docs for Project 1
│   └── self-healing-agents.md   Full docs for Project 2
└── .github/workflows/           CI/CD for both projects
```

---

## Project 1 — AppConfig live-config demo

A Spring Boot app on ECS Fargate that serves a configuration page whose values are read
**dynamically from AWS AppConfig**. All infrastructure is AWS CDK **in Java**, so the entire
solution is Everything-as-Code, built and deployed through a **Jenkins pipeline** (and GitHub
Actions) with a **100% JaCoCo coverage gate** and a **SonarCloud quality gate**. Editing
[`config/runtime-settings.json`](config/runtime-settings.json) and pushing flows the change all
the way to the live page in about a minute.

- **Full documentation:** [docs/appconfig-demo.md](docs/appconfig-demo.md)
- **Run locally** (no AWS needed — serves from a bundled file):
  ```bash
  mvn -pl app spring-boot:run      # http://localhost:8080
  ```
- **Build + enforce 100% coverage:**
  ```bash
  mvn clean verify
  ```

## Project 2 — Self-healing DynamoDB demo

A Spring Boot employee API storing data in **DynamoDB** (H2 for local/test), with Swagger docs.
Its distinguishing feature is a set of Java **agents** that automate bug handling: monitor ECS
health, apply a known validation fix, open a **GitHub PR** and email a human for approval, then —
after merge — deploy via Jenkins, validate the deployment, and **roll ECS back automatically** if
validation fails. Every action is audited in a DynamoDB `AgentAudit` table.

- **Full documentation:** [docs/self-healing-agents.md](docs/self-healing-agents.md)
- **Run locally** (in-memory H2, no AWS needed):
  ```bash
  cd dynamodb-demo
  mvn test
  mvn spring-boot:run -Dspring-boot.run.profiles=local   # http://localhost:8080/swagger-ui.html
  ```
- **Compile the agents:**
  ```bash
  cd agent-runner && mvn compile
  ```

---

## Build everything

Each project builds independently (they intentionally keep their own build rules — Project 1
enforces 100% coverage; Project 2 uses its own Spring Boot 4 build):

```bash
mvn clean verify                          # Project 1: app + infra, 100% coverage gate
( cd dynamodb-demo && mvn clean test )    # Project 2: employee API, 7 integration tests
( cd agent-runner  && mvn clean compile ) # Project 2: self-healing agents
```

## CI/CD

| Workflow | Scope | Trigger |
|---|---|---|
| [ci.yml](.github/workflows/ci.yml) | Project 1: build, 100% coverage gate, SonarCloud quality gate | every push |
| [deploy-app.yml](.github/workflows/deploy-app.yml) | Project 1: build image (Jib) → deploy to Fargate | push to `app/`, `infra/` |
| [deploy-config.yml](.github/workflows/deploy-config.yml) | Project 1: publish config to AppConfig → live | push to `config/` |
| [dynamodb-ci.yml](.github/workflows/dynamodb-ci.yml) | Project 2: build + test the employee API and compile the agents | push to `dynamodb-demo/`, `agent-runner/` |

Project 1 additionally runs on **Jenkins** (polls the repo, auto-builds on every push); Project 2
ships its own [Jenkinsfile](dynamodb-demo/Jenkinsfile) for its build → deploy → validate → rollback
pipeline. See the per-project docs for the full pipeline details.

## Tech stack

Java 17 · Spring Boot (3.5 for Project 1, 4.0 for Project 2) · AWS CDK (Java) · ECS Fargate · ECR ·
AWS AppConfig · DynamoDB · Swagger/OpenAPI · Jib · Jenkins · GitHub Actions · JaCoCo · SonarCloud
