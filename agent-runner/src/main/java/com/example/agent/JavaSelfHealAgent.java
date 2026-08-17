package com.example.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Autopilot self-healing agent.
 *
 * <p>By default it runs on AUTOPILOT: when a build fails it tries many ways to fix it, applies the
 * first fix that works, and pushes it straight to the branch — no human involved. It only escalates
 * to a human (manual closeout + email) when it has exhausted every approach and still cannot fix it.</p>
 *
 * <p>Strategies, tried in order, re-testing after each:</p>
 * <ol>
 *   <li>Known configured patterns (the validation rules in project-config.json).</li>
 *   <li>Patterns it learned on earlier runs (learned-patterns.json).</li>
 *   <li>Restore the affected files to a known-good version, searching back through git history.</li>
 *   <li>Devise a new fix from a built-in fix library — and if it works, <b>learn it</b> (save it to
 *       learned-patterns.json) so next time it is a known pattern.</li>
 * </ol>
 *
 * <p>Pass {@code --assisted} to only propose fixes without pushing.</p>
 */
public final class JavaSelfHealAgent {

    private static final String SOURCE = "java-self-heal-agent";
    private static final int HISTORY_DEPTH = 5;

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
        String emailMode = p.get("email-mode", "auto");
        String baseBranch = p.get("base-branch", "main");
        boolean autopilot = !p.has("assisted");
        boolean dryRun = p.has("dry-run");
        List<String> paths = p.list("paths",
                List.of("dynamodb-demo/src/main/java/com/example/dynamodb_demo/model/Employee.java"));
        List<String> projectRelPaths = projectRelative(paths);
        List<String> testCommand = config.effectiveTestCommand();
        Path learnedPath = configDir.resolve("learned-patterns.json");
        Path closeout = repoDir.resolve("build/MANUAL_CLOSEOUT.md");

        log("Self-heal starting on " + (autopilot ? "AUTOPILOT (will fix and push automatically)" : "ASSISTED")
                + ". A human is contacted only if every strategy fails.");

        if (runTests(projectDir, testCommand)) {
            log("Build is healthy. Nothing to heal, no human needed.");
            audit(region, auditTable, "HEALTHY", "NO_ACTION", "LOW", projectDir.toString(), "Build passed.", dryRun);
            return 0;
        }
        log("Build is FAILING. Attempting automatic repair before escalating.");
        audit(region, auditTable, "BUG_DETECTED", "OPEN", "HIGH", projectDir.toString(),
                "Build/tests failing; autopilot attempting repair.", dryRun);

        List<String> tried = new ArrayList<>();

        // Strategy 1 — known configured patterns.
        log("Strategy 1: known configured patterns...");
        if (applyRules(projectDir, config.rules(), !dryRun) && runTests(projectDir, testCommand)) {
            return resolved(region, auditTable, projectDir, repoDir, paths, baseBranch,
                    "a known configured pattern", tried, autopilot, dryRun);
        }
        tried.add("known configured patterns");

        // Strategy 2 — patterns learned on earlier runs.
        List<AgentConfig.ValidationRule> learned = loadLearnedRules(learnedPath);
        if (!learned.isEmpty()) {
            log("Strategy 2: patterns learned on earlier runs (" + learned.size() + " known)...");
            if (applyRules(projectDir, learned, !dryRun) && runTests(projectDir, testCommand)) {
                return resolved(region, auditTable, projectDir, repoDir, paths, baseBranch,
                        "a pattern it learned on an earlier run", tried, autopilot, dryRun);
            }
            tried.add("previously-learned patterns");
        }

        // Strategy 3 — restore a known-good version, searching back through history.
        log("Strategy 3: searching git history for the last version that builds...");
        if (!dryRun) {
            for (int depth = 1; depth <= HISTORY_DEPTH; depth++) {
                if (restoreFromHistory(repoDir, "HEAD~" + depth, paths) && runTests(projectDir, testCommand)) {
                    return resolved(region, auditTable, projectDir, repoDir, paths, baseBranch,
                            "restoring the last known-good version (HEAD~" + depth + ")", tried, autopilot, dryRun);
                }
            }
        }
        tried.add("history search (HEAD~1.." + HISTORY_DEPTH + ")");

        // Strategy 4 — devise a new fix from the built-in library, then LEARN it.
        log("Strategy 4: devising a new fix and learning it if it works...");
        for (AgentConfig.ValidationRule candidate : builtInFixLibrary(projectRelPaths)) {
            if (applyRules(projectDir, List.of(candidate), !dryRun) && runTests(projectDir, testCommand)) {
                learnPattern(learnedPath, learned, candidate);
                log("Devised a new fix for field '" + candidate.field() + "' and LEARNED it "
                        + "(saved to " + learnedPath.getFileName() + " for next time).");
                return resolved(region, auditTable, projectDir, repoDir, paths, baseBranch,
                        "a new fix it devised and learned (field " + candidate.field() + ")", tried, autopilot, dryRun);
            }
        }
        tried.add("newly-devised fixes from the built-in library");

        // Strategy 5 — guaranteed green: auto-revert the offending commit so main is never left broken.
        if (autopilot && !dryRun) {
            log("Strategy 5: no fix worked. Auto-reverting the offending commit to keep '" + baseBranch + "' green...");
            if (autoRevert(repoDir, projectDir, testCommand)) {
                boolean pushed = rawPush(repoDir, baseBranch);
                log(pushed
                        ? "Auto-reverted the bad commit and pushed. '" + baseBranch + "' is green again. Human notified (FYI only)."
                        : "Auto-revert succeeded locally but the push failed (check git auth).");
                audit(region, auditTable, "RESOLVED_BY_AGENT", "AUTO_REVERTED", "MEDIUM", repoDir.toString(),
                        "Autopilot could not repair the change, so it auto-reverted the offending commit to keep the branch green.", dryRun);
                sendFyiEmail(emailMode, repoDir, dryRun);
                return 0;
            }
            tried.add("auto-revert of the offending commit");
        }

        // Even a revert could not produce a green build → genuine human need.
        log("Every strategy — including an automatic revert — failed. Escalating to a human.");
        writeManualCloseout(closeout, projectDir, tried);
        audit(region, auditTable, "ESCALATED_TO_HUMAN", "MANUAL_ACTION_REQUIRED", "HIGH", projectDir.toString(),
                "Autopilot could not repair the build after trying: " + String.join("; ", tried), dryRun);
        sendEscalationEmail(emailMode, closeout, tried, repoDir, dryRun);
        log("Escalation email sent (only because the agent could not fix it). Closeout: " + closeout);
        return 2;
    }

    private static int resolved(String region, String auditTable, Path projectDir, Path repoDir,
                                List<String> gitPaths, String baseBranch, String how,
                                List<String> tried, boolean autopilot, boolean dryRun) throws Exception {
        log("RESOLVED automatically via " + how + ". No human action required.");
        audit(region, auditTable, "RESOLVED_BY_AGENT", "AUTO_RESOLVED", "MEDIUM", projectDir.toString(),
                "Autopilot fixed the build via " + how + ".", dryRun);
        if (!autopilot || dryRun) {
            log("Assisted/dry-run: fix applied locally but not pushed. Use autopilot to push automatically.");
            return 0;
        }
        boolean pushed = pushFix(repoDir, gitPaths, baseBranch, how);
        if (pushed) {
            log("Fix committed and pushed to '" + baseBranch + "' automatically. The build is healed.");
            audit(region, auditTable, "FIX_PUSHED", "AUTO_PUSHED", "MEDIUM", repoDir.toString(),
                    "Autopilot pushed the fix to " + baseBranch + ".", dryRun);
        } else {
            log("Fix applied but the automatic push failed (check GITHUB_TOKEN / git auth). Fix is in the workspace.");
        }
        return 0;
    }

    private static boolean applyRules(Path projectDir, List<AgentConfig.ValidationRule> rules, boolean applyFix) {
        try {
            return JavaAutoFixAgent.ensureConfiguredValidationRules(projectDir, rules, applyFix).changed();
        } catch (Exception e) {
            log("A fix strategy failed to apply: " + e.getMessage());
            return false;
        }
    }

    private static boolean runTests(Path projectDir, List<String> testCommand) throws Exception {
        return AgentSupport.run(projectDir, testCommand, false).exitCode() == 0;
    }

    private static boolean autoRevert(Path repoDir, Path projectDir, List<String> testCommand) {
        try {
            // discard any partial fix attempts, then revert the offending commit as a new commit
            AgentSupport.run(repoDir, List.of("git", "checkout", "--", "."), false);
            AgentSupport.CommandResult revert = AgentSupport.run(repoDir,
                    List.of("git", "revert", "--no-edit", "HEAD"), false);
            if (revert.exitCode() != 0) {
                AgentSupport.run(repoDir, List.of("git", "revert", "--abort"), false);
                return false;
            }
            return runTests(projectDir, testCommand);
        } catch (Exception e) {
            log("Auto-revert failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean rawPush(Path repoDir, String baseBranch) {
        try {
            String token = firstNonBlank(System.getenv("GH_TOKEN"), System.getenv("GITHUB_TOKEN"));
            List<String> push;
            if (token != null && !token.isBlank()) {
                String origin = AgentSupport.run(repoDir, List.of("git", "remote", "get-url", "origin"), false)
                        .stdout().trim();
                String authUrl = origin.replaceFirst("^https://", "https://x-access-token:" + token + "@");
                push = List.of("git", "push", authUrl, "HEAD:" + baseBranch);
            } else {
                push = List.of("git", "push", "origin", "HEAD:" + baseBranch);
            }
            return AgentSupport.run(repoDir, push, false).exitCode() == 0;
        } catch (Exception e) {
            log("Push failed: " + e.getMessage());
            return false;
        }
    }

    private static void sendFyiEmail(String emailMode, Path repoDir, boolean dryRun) throws Exception {
        String subject = "FYI: autopilot auto-reverted a bad commit (branch kept green)";
        String body = "The autopilot self-heal agent could not repair a failing change, so it automatically "
                + "reverted the offending commit to keep the branch green.\n\n"
                + "No action is required — this is informational. Review the revert when convenient.\n";
        JavaPrApprovalAgent.sendEmail(subject, body, emailMode, dryRun, repoDir.resolve("build/fyi-email.txt"));
    }

    private static boolean restoreFromHistory(Path repoDir, String ref, List<String> paths) {
        try {
            List<String> command = new ArrayList<>(List.of("git", "checkout", ref, "--"));
            command.addAll(paths);
            return AgentSupport.run(repoDir, command, false).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean pushFix(Path repoDir, List<String> gitPaths, String baseBranch, String how) {
        try {
            List<String> add = new ArrayList<>(List.of("git", "add"));
            add.addAll(gitPaths);
            AgentSupport.run(repoDir, add, false);
            AgentSupport.run(repoDir, List.of("git", "commit", "-m",
                    "fix: self-heal autopilot resolved the build via " + how), false);
            String token = firstNonBlank(System.getenv("GH_TOKEN"), System.getenv("GITHUB_TOKEN"));
            List<String> push;
            if (token != null && !token.isBlank()) {
                String origin = AgentSupport.run(repoDir, List.of("git", "remote", "get-url", "origin"), false)
                        .stdout().trim();
                String authUrl = origin.replaceFirst("^https://", "https://x-access-token:" + token + "@");
                push = List.of("git", "push", authUrl, "HEAD:" + baseBranch);
            } else {
                push = List.of("git", "push", "origin", "HEAD:" + baseBranch);
            }
            return AgentSupport.run(repoDir, push, false).exitCode() == 0;
        } catch (Exception e) {
            log("Push failed: " + e.getMessage());
            return false;
        }
    }

    private static List<AgentConfig.ValidationRule> loadLearnedRules(Path learnedPath) {
        try {
            if (Files.exists(learnedPath)) {
                return AgentConfig.load(learnedPath).rules();
            }
        } catch (Exception e) {
            log("Could not read learned patterns: " + e.getMessage());
        }
        return List.of();
    }

    private static void learnPattern(Path learnedPath, List<AgentConfig.ValidationRule> existing,
                                     AgentConfig.ValidationRule newRule) {
        try {
            List<AgentConfig.ValidationRule> all = new ArrayList<>(existing);
            boolean known = all.stream().anyMatch(r ->
                    r.field().equals(newRule.field()) && r.annotation().equals(newRule.annotation()));
            if (!known) {
                all.add(newRule);
            }
            StringBuilder sb = new StringBuilder("{\n  \"validationRules\": [\n");
            for (int i = 0; i < all.size(); i++) {
                AgentConfig.ValidationRule r = all.get(i);
                sb.append("    {")
                        .append("\"name\":\"").append(esc(r.name())).append("\",")
                        .append("\"type\":\"java-field-annotation\",")
                        .append("\"file\":\"").append(esc(r.file())).append("\",")
                        .append("\"field\":\"").append(esc(r.field())).append("\",")
                        .append("\"requiredImport\":\"").append(esc(r.requiredImport())).append("\",")
                        .append("\"annotation\":\"").append(esc(r.annotation())).append("\"}")
                        .append(i < all.size() - 1 ? ",\n" : "\n");
            }
            sb.append("  ]\n}\n");
            Files.writeString(learnedPath, sb.toString());
        } catch (Exception e) {
            log("Could not save learned pattern: " + e.getMessage());
        }
    }

    /** Built-in fixes the agent can try even when no configured/learned rule matches. */
    private static List<AgentConfig.ValidationRule> builtInFixLibrary(List<String> projectRelPaths) {
        String employee = projectRelPaths.stream()
                .filter(pth -> pth.endsWith("Employee.java"))
                .findFirst()
                .orElse("src/main/java/com/example/dynamodb_demo/model/Employee.java");
        return List.of(
                rule("innate-address-pattern", employee, "address",
                        "@Pattern(regexp = \"^[a-zA-Z0-9 ,.-]+$\", message = \"address contains invalid characters\")"),
                rule("innate-name-pattern", employee, "name",
                        "@Pattern(regexp = \"^[a-zA-Z .'-]+$\", message = \"name contains invalid characters\")"));
    }

    private static AgentConfig.ValidationRule rule(String name, String file, String field, String annotation) {
        return new AgentConfig.ValidationRule(name, "java-field-annotation", file, field,
                "jakarta.validation.constraints.Pattern", annotation, "VALIDATION", "MEDIUM", null, 400, null);
    }

    private static void writeManualCloseout(Path closeout, Path projectDir, List<String> tried) {
        try {
            Files.createDirectories(closeout.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# Manual closeout — autopilot could not fix this build\n\n");
            sb.append("Generated: ").append(AgentSupport.utcNow()).append("\n\n");
            sb.append("The autopilot self-heal agent detected a failing build and tried every strategy it has, ");
            sb.append("but could not repair it. **A human needs to take over.**\n\n");
            sb.append("## Approaches the agent tried\n");
            for (String t : tried) {
                sb.append("- ").append(t).append("\n");
            }
            sb.append("\n## Suggested next steps\n");
            sb.append("1. Read the first failing test/compile error in the build log.\n");
            sb.append("2. Reproduce locally in `").append(projectDir).append("`.\n");
            sb.append("3. Fix the root cause, or revert the offending commit.\n");
            sb.append("4. Add a rule to `agent-config/project-config.json` so the agent can fix it automatically next time.\n");
            Files.writeString(closeout, sb.toString());
        } catch (Exception e) {
            log("Could not write manual closeout: " + e.getMessage());
        }
    }

    private static void sendEscalationEmail(String emailMode, Path closeout, List<String> tried,
                                            Path repoDir, boolean dryRun) throws Exception {
        String subject = "ACTION NEEDED: autopilot could not fix the build";
        StringBuilder body = new StringBuilder();
        body.append("The autopilot self-heal agent could not repair the build after trying every approach:\n");
        for (String t : tried) {
            body.append("  - ").append(t).append("\n");
        }
        body.append("\nManual closeout report: ").append(closeout).append("\n");
        body.append("\nThis email is sent ONLY because the agent could not fix it itself.\n");
        JavaPrApprovalAgent.sendEmail(subject, body.toString(), emailMode, dryRun,
                repoDir.resolve("build/escalation-email.txt"));
    }

    private static List<String> projectRelative(List<String> paths) {
        List<String> out = new ArrayList<>();
        for (String pth : paths) {
            int idx = pth.indexOf("src/");
            out.add(idx >= 0 ? pth.substring(idx) : pth);
        }
        return out;
    }

    private static void audit(String region, String table, String eventType, String status, String severity,
                              String resource, String message, boolean dryRun) {
        AuditWriter.put(region, table, SOURCE, eventType, status, "VALIDATION", severity, resource, message, dryRun);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
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
