import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassWriter;

/**
 * Compare application classes independently of source line numbers, local
 * variable tables and compiler-emitted method parameter metadata.
 * Requires the spring-core JAR from the audited application's BOOT-INF/lib.
 */
public class ProductionClassAudit {
    static String canonical(Path path) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(Files.readAllBytes(path)).accept(writer, ClassReader.SKIP_DEBUG);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(writer.toByteArray()));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException(
                "Usage: ProductionClassAudit live-classes rebuilt-classes output.sha256");
        Path live = Path.of(args[0]), rebuilt = Path.of(args[1]);
        int count = 0, same = 0;
        List<String> different = new ArrayList<>(), missing = new ArrayList<>(), extra = new ArrayList<>();
        Map<String, String> hashes = new TreeMap<>();
        try (var paths = Files.walk(live)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                String relative = live.relativize(path).toString();
                Path build = rebuilt.resolve(relative);
                count++;
                String hash = canonical(path);
                hashes.put(relative, hash);
                if (!Files.exists(build)) missing.add(relative);
                else if (hash.equals(canonical(build))) same++;
                else different.add(relative);
            }
        }
        try (var paths = Files.walk(rebuilt)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                String relative = rebuilt.relativize(path).toString();
                if (!Files.exists(live.resolve(relative))) extra.add(relative);
            }
        }
        System.out.println("productionClasses=" + count);
        System.out.println("canonicalMatches=" + same);
        System.out.println("different=" + different);
        System.out.println("missing=" + missing);
        System.out.println("extra=" + extra);
        StringBuilder manifest = new StringBuilder();
        hashes.forEach((path, hash) -> manifest.append(hash).append("  ").append(path).append("\n"));
        Files.writeString(Path.of(args[2]), manifest);
        if (!different.isEmpty() || !missing.isEmpty() || !extra.isEmpty())
            throw new IllegalStateException("Production application class mismatch");
    }
}
