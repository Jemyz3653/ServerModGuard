package io.github.jemyz3653.servermodguard;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

public final class PendingUpdates {
    static final int MAX_JAR_BYTES = 32 * 1024 * 1024;

    private PendingUpdates() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2 || !args[0].equals("--apply")) {
            System.err.println("Usage: java -jar servermodguard.jar --apply [server-directory]");
            System.exit(2);
        }
        Path root = Path.of(args.length == 2 ? args[1] : ".").toAbsolutePath().normalize();
        int applied = apply(root);
        System.out.println("Server Mod Guard applied " + applied + " update(s).");
    }

    static void stage(Path config, String id, Path original, String version, String digest, Path downloaded) throws IOException {
        Files.createDirectories(config);
        try (var lockFile = FileChannel.open(config.resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = lockFile.lock()) {
            Path pending = config.resolve("pending");
            Files.createDirectories(pending);
            Properties info = new Properties();
            info.setProperty("file", original.getFileName().toString());
            info.setProperty("oldSha256", sha256(original));
            info.setProperty("newSha256", digest);
            info.setProperty("version", version);

            Path jar = pending.resolve(id + "-" + digest + ".jar");
            replace(downloaded, jar);
            Path temp = Files.createTempFile(pending, id + "-", ".tmp");
            try {
                try (var out = Files.newOutputStream(temp)) {
                    info.store(out, null);
                }
                replace(temp, pending.resolve(id + ".properties"));
            } finally {
                Files.deleteIfExists(temp);
            }
            try (var files = Files.newDirectoryStream(pending, id + "-*.jar")) {
                var prefix = id + "-";
                for (Path old : files) {
                    String name = old.getFileName().toString();
                    if (!old.equals(jar) && name.substring(prefix.length()).matches("[0-9a-f]{64}\\.jar")) {
                        Files.deleteIfExists(old);
                    }
                }
            }
        }
    }

    static int apply(Path root) throws IOException {
        Path config = root.resolve("config/servermodguard");
        Path pending = config.resolve("pending");
        if (Files.notExists(pending)) {
            return 0;
        }
        try (var lockFile = FileChannel.open(config.resolve("update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = lockFile.lock();
             var manifests = Files.newDirectoryStream(pending, "*.properties")) {
            int count = 0;
            for (Path manifest : manifests) {
                if (install(root, config, pending, manifest)) {
                    count++;
                }
            }
            return count;
        }
    }

    private static boolean install(Path root, Path config, Path pending, Path manifest) throws IOException {
        Properties properties = new Properties();
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.size(manifest) > 16_384) {
            throw new IOException("Invalid pending update record: " + manifest.getFileName());
        }
        try (var in = Files.newInputStream(manifest)) {
            properties.load(in);
        }
        String id = manifest.getFileName().toString().replaceFirst("\\.properties$", "");
        String file = properties.getProperty("file", "");
        String oldHash = properties.getProperty("oldSha256", "");
        String newHash = properties.getProperty("newSha256", "");
        if (!id.matches("[a-z][a-z0-9_-]{1,63}") || !file.matches("[A-Za-z0-9][A-Za-z0-9_.+() -]{0,220}\\.jar")
            || !oldHash.matches("[0-9a-f]{64}") || !newHash.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid pending update record: " + manifest.getFileName());
        }

        Path current = root.resolve("mods").resolve(file);
        Path jar = pending.resolve(id + "-" + newHash + ".jar");
        if (sha256(current).equals(newHash)) {
            Files.deleteIfExists(jar);
            Files.delete(manifest);
            return false;
        }
        if (!sha256(current).equals(oldHash) || !sha256(jar).equals(newHash)) {
            throw new IOException("The pending JAR or installed mod changed since staging. Kept " + file);
        }

        Path backups = config.resolve("backups");
        Files.createDirectories(backups);
        Path backup = backups.resolve(id + "-" + oldHash + ".jar");
        if (Files.notExists(backup)) {
            Path temp = Files.createTempFile(backups, id + "-", ".tmp");
            try {
                Files.copy(current, temp, StandardCopyOption.REPLACE_EXISTING);
                replace(temp, backup);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        if (!sha256(backup).equals(oldHash)) {
            throw new IOException("Backup verification failed for " + file);
        }

        Path temp = Files.createTempFile(current.getParent(), id + "-", ".tmp");
        try {
            Files.copy(jar, temp, StandardCopyOption.REPLACE_EXISTING);
            replace(temp, current);
        } finally {
            Files.deleteIfExists(temp);
        }
        Files.delete(manifest);
        Files.deleteIfExists(jar);
        System.out.println("Updated " + id + " to " + properties.getProperty("version", "") + ". Backup: " + backup);
        return true;
    }

    static String sha256(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_JAR_BYTES) {
            throw new IOException("Invalid or missing JAR: " + file.getFileName());
        }
        try (var stream = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16 * 1024];
            for (int length; (length = stream.read(buffer)) != -1;) {
                digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static void replace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unavailable) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
