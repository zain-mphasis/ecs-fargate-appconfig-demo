#!/usr/bin/env bash
# Jenkins autopilot self-heal entry point.
# Runs JavaSelfHealAgent, which itself detects a failing build, tries many
# strategies, fixes + pushes automatically, and escalates (email) only when stuck.
#
# Exit codes it passes through:
#   0 = build healthy OR auto-fixed and pushed
#   2 = could not fix -> escalated to a human (email sent, manual closeout written)
set +e

export GH_TOKEN="${GH_TOKEN:-$GITHUB_TOKEN}"
export SMTP_HOST="${SMTP_HOST:-smtp.gmail.com}"
export SMTP_PORT="${SMTP_PORT:-587}"

cd agent-runner
mvn -q compile exec:java \
  -Dexec.mainClass=com.example.agent.JavaSelfHealAgent \
  -Dexec.args='--config ../agent-config/project-config.json --project-dir ../dynamodb-demo --repo-dir .. --base-branch main --region us-east-1 --audit-table AgentAudit'
exit $?
