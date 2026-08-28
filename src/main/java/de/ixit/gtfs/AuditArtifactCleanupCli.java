package de.ixit.gtfs;

import java.nio.file.Path;

public final class AuditArtifactCleanupCli {
    private AuditArtifactCleanupCli() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            AuditArtifactCleanupReport report = AuditArtifactCleaner.clean(
                    options.toolRoot(),
                    options.auditRoot(),
                    options.execute(),
                    options.deleteInputCopies()
            );
            System.out.println("Audit artifact cleanup "
                    + (options.execute() ? "executed" : "dry-run")
                    + ": candidates="
                    + report.candidateCount()
                    + ", bytes="
                    + report.candidateBytes()
                    + ", deleted="
                    + report.deletedCount());
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
        } catch (Exception ex) {
            System.err.println("Audit artifact cleanup failed: " + ex.getMessage());
            System.exit(1);
        }
    }

    private record Options(Path toolRoot, Path auditRoot, boolean execute, boolean deleteInputCopies) {
        private static Options parse(String[] args) {
            Path toolRoot = null;
            Path auditRoot = null;
            boolean execute = false;
            boolean deleteInputCopies = false;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--tool-root" -> toolRoot = requirePath(args, ++index, "--tool-root");
                    case "--audit-root" -> auditRoot = requirePath(args, ++index, "--audit-root");
                    case "--execute" -> execute = true;
                    case "--delete-input-copies" -> deleteInputCopies = true;
                    case "--help", "-h" -> throw new IllegalArgumentException("IXIT audit artifact cleanup");
                    default -> throw new IllegalArgumentException("Unknown cleanup option: " + args[index]);
                }
            }
            if (toolRoot == null || auditRoot == null) {
                throw new IllegalArgumentException("Both --tool-root and --audit-root are required");
            }
            return new Options(toolRoot, auditRoot, execute, deleteInputCopies);
        }

        private static Path requirePath(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("-")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return Path.of(args[index]);
        }

        private static String usage() {
            return "Usage: cleanup-audit --tool-root PATH --audit-root PATH [--execute] [--delete-input-copies]";
        }
    }
}
