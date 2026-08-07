#!/usr/bin/env bash
# Invoked by the Jenkins self-healing pipeline when the DynamoDB build fails.
# Runs the orchestrator agent: auto-fix the configured validation bug, run tests,
# open a GitHub PR, and prepare an approval email. Needs GITHUB_TOKEN in the env.
set -e

BUILD_NUMBER="${1:-manual}"
FIX_BRANCH="jenkins-autofix-${BUILD_NUMBER}"
export GH_TOKEN="${GH_TOKEN:-$GITHUB_TOKEN}"
export SMTP_HOST="${SMTP_HOST:-smtp.gmail.com}"
export SMTP_PORT="${SMTP_PORT:-587}"

echo "Self-heal: running agent, fix branch = ${FIX_BRANCH}"

cd agent-runner
mvn -q compile exec:java \
  -Dexec.mainClass=com.example.agent.JavaOrchestratorAgent \
  -Dexec.args='--skip-monitor --email-mode smtp --config ../agent-config/project-config.json --repo-dir .. --project-dir ../dynamodb-demo --base-branch main --fix-branch '"${FIX_BRANCH}"' --paths dynamodb-demo/src/main/java/com/example/dynamodb_demo/model/Employee.java --pr-title "Jenkins self-heal: restore employee validation" --summary "Jenkins detected a failing build; the agent restored the validation, ran the tests, and opened a PR for human approval."'
