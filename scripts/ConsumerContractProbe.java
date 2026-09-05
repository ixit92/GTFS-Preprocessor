import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/** Runs only the copied consumer's database validator, never its server entry point. */
class ConsumerContractProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        Path database = Path.of(args[1]).toRealPath();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        if (!database.startsWith(root.resolve("build")) || !output.getParent().toRealPath().startsWith(root.resolve("build"))
                || Files.exists(output)) throw new IllegalArgumentException("Fresh output and isolated build database required");
        Class<?> store = Class.forName("de.ixit.transit.api.VersionedDataStore");
        Path jar = Path.of(store.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!jar.startsWith(root.resolve("local-data"))) throw new IllegalArgumentException("Consumer must be an isolated artifact copy");
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(jar)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = stream.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        if (!hash.equals(args[3])) throw new IllegalArgumentException("Copied consumer hash mismatch");
        var supported = store.getDeclaredField("SUPPORTED_CONTRACT_VERSIONS");
        supported.setAccessible(true);
        var load = store.getDeclaredMethod("loadDirectDatabase", Path.class);
        load.setAccessible(true);
        Object version = load.invoke(null, database);
        var contractAccessor = version.getClass().getDeclaredMethod("contractVersion");
        contractAccessor.setAccessible(true);
        String contract = (String) contractAccessor.invoke(version);
        if (!"0.9".equals(contract)) throw new IllegalArgumentException("This probe requires an isolated Contract 0.9 database");
        var validate = store.getDeclaredMethod("validateDatabase", version.getClass());
        validate.setAccessible(true);
        var report = new LinkedHashMap<String, Object>();
        report.put("consumer_jar_sha256", hash);
        report.put("supported_contract_versions", supported.get(null));
        report.put("database", database.toString());
        report.put("tested_contract_version", contract);
        report.put("scope", "copied_release_artifact_database_validator_only");
        report.put("running_container_binary_verified", false);
        report.put("activation_allowed", false);
        try {
            validate.invoke(null, version);
            report.put("consumer_accepts_database", true);
            report.put("status", "VALIDATOR_ACCEPTED_NOT_ACTIVATION_APPROVAL");
        } catch (InvocationTargetException failure) {
            report.put("consumer_accepts_database", false);
            report.put("status", "VALIDATOR_REJECTED");
            report.put("exception_type", failure.getCause().getClass().getName());
            report.put("reason", failure.getCause().getMessage());
        }
        var json = new ObjectMapper().writerWithDefaultPrettyPrinter();
        Files.writeString(output, json.writeValueAsString(report));
        System.out.println(json.writeValueAsString(report));
    }
}
