import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;

/** Source-file launcher; verifies the complete runtime set without third-party code. */
class VerifyDependencies {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected jar and lock file");
        Map<String,String> expected=new TreeMap<>(), actual=new TreeMap<>();
        for(String line:Files.readAllLines(Path.of(args[1]))) {
            if(line.isBlank() || line.startsWith("#"))continue;
            String[] values=line.split("  ",2);
            if(values.length!=2 || !values[0].matches("[a-f0-9]{64}") || !values[1].matches("BOOT-INF/lib/[A-Za-z0-9_.+-]+\\.jar"))
                throw new IllegalArgumentException("Invalid dependency lock entry");
            if(expected.put(values[1],values[0])!=null)throw new IllegalArgumentException("Duplicate dependency");
        }
        try(var jar=new JarFile(args[0])) {
            var entries=jar.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();
                if(entry.isDirectory() || !entry.getName().startsWith("BOOT-INF/lib/"))continue;
                var digest=MessageDigest.getInstance("SHA-256");
                try(var input=jar.getInputStream(entry)) {
                    byte[] buffer=new byte[65536];int size;
                    while((size=input.read(buffer))!=-1)digest.update(buffer,0,size);
                }
                actual.put(entry.getName(),HexFormat.of().formatHex(digest.digest()));
            }
        }
        if(expected.isEmpty() || !expected.equals(actual)) {
            var changed=new java.util.TreeSet<String>();changed.addAll(expected.keySet());changed.addAll(actual.keySet());
            changed.removeIf(name->java.util.Objects.equals(expected.get(name),actual.get(name)));
            throw new IllegalStateException("Runtime dependency drift: "+changed);
        }
        System.out.println("Verified "+actual.size()+" locked runtime dependencies");
    }
}
