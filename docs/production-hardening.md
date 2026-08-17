# Production Hardening

This project is built to demonstrate a full cloud-native delivery loop, with a deliberately
**maximally-autonomous** self-heal agent (it pushes fixes straight to `main`). This document lists
what you would change to run it in a real production environment, and — importantly — where those
choices trade off against the demo's "full-control autopilot" behaviour.

## Already in place
- **Least-privilege IAM.** The Fargate task role can call exactly two AppConfig actions on exactly one
  configuration profile — not `appconfig:*`, not account-wide. Deploys use a scoped GitHub OIDC role.
- **No long-lived keys in CI.** GitHub Actions authenticates to AWS with OIDC (no stored access keys).
- **Secrets outside code.** The GitHub token and SMTP credentials live in Jenkins' encrypted
  credential store and GitHub Actions secrets, never in the repo.
- **Config safety.** A gradual AppConfig rollout strategy (`ecs-fargate-demo-gradual`) plus a
  CloudWatch alarm wired as the environment monitor auto-rolls-back a bad configuration.
- **Observability.** CloudWatch alarms on unhealthy tasks and load-balancer 5xx errors.
- **Quality gates.** 100% line+branch coverage (JaCoCo) and a SonarCloud quality gate that blocks
  merges/releases.

## The autopilot vs. branch-protection decision
The self-heal agent runs **full-control autopilot**: it fixes failing builds and **pushes directly to
`main`**, and as a last resort auto-reverts a bad commit. That is impressive for a demo, but it is the
*opposite* of classic branch protection.

For production you would pick one of:
1. **Protected `main` + PR-based agent (recommended for real teams).** Turn on branch protection
   (require PR, require the CI + Sonar checks to pass, require a review). Change the agent to open a
   **pull request** with its fix instead of pushing to `main`. A human (or an auto-merge rule gated on
   green checks) merges. Nothing unreviewed ever reaches production.
2. **Keep autopilot, but on a `release` branch, not `main`.** Let the agent push freely to an
   integration branch; promote to the protected production branch only via reviewed PRs.

You cannot have both "agent pushes straight to `main`" and "everything on `main` is reviewed" — that is
a genuine design choice, not an oversight.

## Recommended next steps for production
| Area | Change |
|---|---|
| Branch protection | Require PRs + passing CI/Sonar + 1 review on `main`; switch the agent to PR mode. |
| Secrets | Move SMTP/GitHub credentials to **AWS Secrets Manager**; have Jenkins/agents read at runtime. |
| Transport security | Put **HTTPS** on the load balancer (ACM certificate + HTTPS listener); redirect 80→443. |
| Least privilege (CI) | Scope the `github-actions-deploy` role down from `PowerUserAccess` to the exact actions used. |
| Network | Keep tasks in private subnets (already via the VPC); restrict the ALB security group to expected sources. |
| Rollout | Use the **gradual** strategy for real config changes (all-at-once is only for the demo's instant effect). |
| Observability | Add a CloudWatch dashboard + alarm actions (SNS → Slack/email/PagerDuty); ship logs to a log group with retention. |
| Agent guardrails | Cap what the agent may change (path allow-list — already partly done), require the audit trail (already in DynamoDB), and add rate limits so a bad loop can't push repeatedly. |
| Supply chain | Pin action/plugin versions by SHA; enable Dependabot; scan images (ECR scan-on-push is already enabled). |
