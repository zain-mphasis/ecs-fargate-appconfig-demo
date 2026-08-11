package com.example.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Escalation-aware self-healing agent.
 *
 * <p>Unlike the plain auto-fix workflow (which always opens a PR and emails a human), this agent
 * only bothers a human when it genuinely cannot fix the problem itself. Its decision flow:</p>
 *
 * <ol>
 *   <li>Run the build/tests. If they pass, do nothing (no email).</li>
 *   <li>Strategy 1 — apply the known configured patterns (the validation rules).</li>
 *   <li>Strategy 2 — if that did not work, try an alternative method: restore the affected files
 *       to the last known-good version from git. If this works, the agent <b>records the new
 *       solution it discovered</b> to {@code learned-patterns.md} so it is remembered next time.</li>
 *   <li>If a strategy fixed it, open a PR for the record but send <b>no escalation email</b>.</li>
 *   <li>Only if every strategy fails does the agent escalate: write a <b>manual closeout</b>
 *       report and send the approval/help email. That is the single case where a human is needed.</li>
 * </ol>
 *
 * <p>It is <b>not</b> on autopilot by default: pass {@code --autopilot} to let it apply and push
 * fixes; otherwise it runs in assisted mode and proposes changes without pushing.</p>
 */
public final class JavaSelfHealAgent {

    private static final String SOURCE = "java-self-heal-agent";

    private JavaSelfHealAgent() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(execute(args));
    }

    public static int execute(String[] args) throws Exception {
        AgentArgs p = AgentArgs.parse(args);
        Path configPath = Path.of(p.get("config", AgentConfig.defaultConfigPath().toString()))
                .toAbsolutePath().normalize();
        AgentConfig config = AgentConfig.load(configPath);
        Path configDir = configPath.getParent();
        Path projectDir = Path.of(p.get("project-dir", resolve(configDir, config.projectDir()).toString()))
                .toAbsolutePath().normalize();
        Path repoDir = Path.of(p.get("repo-dir", "..")).toAbsolutePath().normalize();
        String region = p.get("region", config.aws().region());
        String auditTable = p.get("audit-table", config.aws().auditTable());
        // "auto": send a real email when SMTP is configured, otherwise write a preview file
        // (escalation must never crash just because email isn't set up).
        String emailMode = p.get("email-mode", "auto");
        boolean autopilot = p.has("autopilot");
        boolean dryRun = p.has("dry-run");
        List<String> paths = p.list("paths",
                List.of("dynamodb-demo/src/main/java/com/example/dynamodb_demo/model/Employee.java"));
        List<String> testCommand = config.effectiveTestCommand();
        Path learned = configDir.resolve("learned-patterns.md");
        Path closeout = repoDir.resolve("build/MANUAL_CLOSEOUT.md");

        log("Self-heal starting in " + (autopilot ? "AUTOPILOT" : "ASSISTED") + " mode. "
                + "A human is contacted only if the agent cannot fix the problem itself.");

        // Step 1 — is anything actually broken?
        if (runTests(projectDir, testCommand)) {
            log("Build is healthy. Nothing to heal, no human needed, no email sent.");
            audit(region, auditTable, "HEALTHY", "NO_ACTION", "LOW", projectDir.toString(),
                    "Build passed; self-heal agent idle.", dryRun);
            return 0;
        }
        log("Build is FAILING. The agent will try to fix it before escalating to a human.");
        audit(region, auditTable, "BUG_DETECTED", "OPEN", "HIGH", projectDir.toString(),
                "Build/tests failing; self-heal attempting automatic repair.", dryRun);

        List<String> tried = new ArrayList<>();

        // Strategy 1 — known configured patterns.
        log("Strategy 1: applying known configured patterns...");
        JavaAutoFixAgent.FixResult known = JavaAutoFixAgent.ensureConfiguredValidationRules(
                projectDir, config.rules(), !dryRun);
        tried.add("known-patterns -> " + known.message());
        if (known.changed() && runTests(projectDir, testCommand)) {
            return resolvedByAgent(p, region, auditTable, projectDir, repoDir, paths,
                    "known configured pattern", tried, autopilot, dryRun);
        }

        // Strategy 2 — alternative method: restore affected files from the last known-good commit.
        log("Strategy 2: known patterns did not resolve it. Trying an alternative method "
                + "(restore last known-good version)...");
        if (!dryRun && restoreLastKnownGood(repoDir, paths)) {
            tried.add("alternative-method -> restored last known-good version from git (HEAD~1)");
            if (runTests(projectDir, testCommand)) {
                recordLearnedPattern(learned, paths, tried);
                log("The alternative method worked. The agent recorded this solution to "
                        + learned.getFileName() + " so it is reused next time.");
                return resolvedByAgent(p, region, auditTable, projectDir, repoDir, paths,
                        "alternative method (restored last known-good) + learned a new pattern",
                        tried, autopilot, dryRun);
            }
        }

        // Every strategy failed → this is a genuine MANUAL need.
        log("The agent exhausted its strategies and CANNOT fix this automatically. Escalating to a human.");
        writeManualCloseout(closeout, projectDir, tried);
        audit(region, auditTable, "ESCALATED_TO_HUMAN", "MANUAL_ACTION_REQUIRED", "HIGH",
                projectDir.toString(),
                "Self-heal could not repair the build. Manual closeout written; human approval required.", dryRun);
        sendEscalationEmail(emailMode, closeout, tried, repoDir, dryRun);
        log("Escalation email sent (only because the agent could not fix it). See "
                + closeout + " for the manual closeout report.");
        return 2;
    }

    private static int resolvedByAgent(AgentArgs p, String region, String auditTable, Path projectDir,
                                       Path repoDir, List<String> paths, String how, List<String> tried,
                                       boolean autopilot, boolean dryRun) throws Exception {
        log("RESOLVED automatically by the agent via " + how + ". No human action required, no email sent.");
        audit(region, auditTable, "RESOLVED_BY_AGENT", "AUTO_RESOLVED", "MEDIUM", projectDir.toString(),
                "Self-heal fixed the build via " + how + ". Tried: " + String.join("; ", tried), dryRun);

        if (!autopilot) {
            log("Assisted mode: the fix is applied locally and proposed, but not pushed. "
                    + "Run with --autopilot to open a PR automatically.");
            return 0;
        }
        // Autopilot: open a PR for the record, but with email in preview mode (no escalation email).
        if (!JavaPrApprovalAgent.hasChanges(repoDir, paths)) {
            log("No net source change to propose after healing.");
            return 0;
        }
        List<String> prArgs = new ArrayList<>(List.of(
                "--repo-dir", repoDir.toString(),
                "--base-branch", p.get("base-branch", "main"),
                "--branch", p.get("fix-branch", "self-heal-auto"),
                "--commit-message", "fix: self-heal auto-resolved the build",
                "--pr-title", "Self-heal: auto-resolved (" + how + ")",
                "--pr-body", "Resolved automatically by the self-heal agent. No manual intervention was required.",
                "--summary", "Self-heal resolved the build automatically via " + how + ".",
                "--email-mode", "preview",
                "--outbox-file", "build/self-heal-note.txt",
                "--region", region,
                "--audit-table", auditTable));
        if (!paths.isEmpty()) {
            prArgs.add("--paths");
            prArgs.addAll(paths);
        }
        JavaPrApprovalAgent.execute(prArgs.toArray(String[]::new));
        return 0;
    }

    private static boolean runTests(Path projectDir, List<String> testCommand) throws Exception {
        AgentSupport.CommandResult result = AgentSupport.run(projectDir, testCommand, false);
        return result.exitCode() == 0;
    }

    private static boolean restoreLastKnownGood(Path repoDir, List<String> paths) {
        try {
            List<String> command = new ArrayList<>(List.of("git", "checkout", "HEAD~1", "--"));
            command.addAll(paths);
            AgentSupport.CommandResult result = AgentSupport.run(repoDir, command, false);
            return result.exitCode() == 0;
        } catch (Exception e) {
            log("Alternative method could not run git restore: " + e.getMessage());
            return false;
        }
    }

    private static void recordLearnedPattern(Path learned, List<String> paths, List<String> tried) {
        try {
            String entry = "\n## Learned " + AgentSupport.utcNow() + "\n"
                    + "- Trigger: build failed and the known patterns did not fix it.\n"
                    + "- Files: " + String.join(", ", paths) + "\n"
                    + "- Solution that worked: restore the affected file(s) to the last known-good git version.\n"
                    + "- Steps tried before this worked: " + String.join("; ", tried) + "\n";
            if (Files.notExists(learned)) {
                Files.writeString(learned, "# Patterns the self-heal agent has learned\n");
            }
            Files.writeString(learned, entry, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            log("Could not record learned pattern: " + e.getMessage());
        }
    }

    private static void writeManualCloseout(Path closeout, Path projectDir, List<String> tried) {
        try {
            Files.createDirectories(closeout.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# Manual closeout — self-heal agent could not fix this build\n\n");
            sb.append("Generated: ").append(AgentSupport.utcNow()).append("\n\n");
            sb.append("The automated self-heal agent detected a failing build and tried to repair it, ");
            sb.append("but none of its strategies worked. **A human needs to take over.**\n\n");
            sb.append("## What the agent tried (in order)\n");
            for (String t : tried) {
                sb.append("- ").append(t).append("\n");
            }
            sb.append("\n## Suggested next steps for the human\n");
            sb.append("1. Open the failing build's console log and read the first failing test/compile error.\n");
            sb.append("2. Reproduce locally: run the project's tests in `").append(projectDir).append("`.\n");
            sb.append("3. Fix the root cause, or revert the offending commit if it is not easily fixable.\n");
            sb.append("4. Once fixed, if the same problem recurs, add a new rule to `agent-config/project-config.json` ");
            sb.append("so the agent can fix it automatically next time.\n");
            Files.writeString(closeout, sb.toString());
        } catch (Exception e) {
            log("Could not write manual closeout: " + e.getMessage());
        }
    }

    private static void sendEscalationEmail(String emailMode, Path closeout, List<String> tried,
                                            Path repoDir, boolean dryRun) throws Exception {
        String subject = "ACTION NEEDED: self-heal agent could not fix the build";
        StringBuilder body = new StringBuilder();
        body.append("The automated self-heal agent detected a failing build and could not repair it ");
        body.append("after trying every strategy it knows. Human help is required.\n\n");
        body.append("What the agent tried:\n");
        for (String t : tried) {
            body.append("  - ").append(t).append("\n");
        }
        body.append("\nA manual closeout report was written to: ").append(closeout).append("\n");
        body.append("\nThis email is sent ONLY because the agent could not fix the problem itself.\n");
        Path outbox = repoDir.resolve("build/escalation-email.txt");
        JavaPrApprovalAgent.sendEmail(subject, body.toString(), emailMode, dryRun, outbox);
    }

    private static void audit(String region, String table, String eventType, String status, String severity,
                              String resource, String message, boolean dryRun) {
        AuditWriter.put(region, table, SOURCE, eventType, status, "VALIDATION", severity, resource, message, dryRun);
    }

    private static Path resolve(Path baseDir, String value) {
        if (value == null || value.isBlank()) {
            return baseDir;
        }
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : baseDir.resolve(path).normalize();
    }

    private static void log(String message) {
        System.out.printf("[%s] %s%n", AgentSupport.utcNow(), message);
    }
}
