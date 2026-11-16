package io.github.jemyz3653.servermodguard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

final class ModUpdater {
    private static final int MAX_CATALOG_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DESCRIPTOR_BYTES = 128 * 1024;
    private static final Set<String> CATALOG_HOSTS = Set.of("raw.githubusercontent.com", "github.com", "release-assets.githubusercontent.com");
    private static final Set<String> DOWNLOAD_HOSTS = Set.of(
        "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
        "cdn.modrinth.com", "modrinth.com",
        "www.curseforge.com", "curseforge.com", "edge.forgecdn.net", "mediafilez.forgecdn.net", "media.forgecdn.net"
    );

    private record Catalog(int schema, List<Project> projects) {}
    private record Project(String id, List<Release> releases) {}
    private record Release(String version, List<String> minecraft, String loader, Boolean prerelease,
                           String url, String sha256, int bytes) {}
    private record Candidate(ModContainer mod, Path path, Release release, SemanticVersion version) {}
    private record Prepared(Candidate candidate, Path file, Version version, JsonObject descriptor) {}

    private final FabricLoader loader;
    private final Path config;
    private final GuardConfig.Updates updates;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name("servermodguard-update").factory()
    );

    ModUpdater(FabricLoader loader, Path config, GuardConfig.Updates updates) {
        this.loader = loader;
        this.config = config;
        this.updates = updates;
    }

    void start() {
        executor.scheduleWithFixedDelay(this::check, 2, updates.checkMinutes() * 60L, TimeUnit.SECONDS);
    }

    void stop() {
        executor.shutdownNow();
    }

    private void check() {
        try {
            Map<String, Version> loaded = new LinkedHashMap<>();
            for (var mod : loader.getAllMods()) {
                loaded.put(mod.getMetadata().getId(), mod.getMetadata().getVersion());
            }
            String minecraft = loaded.get("minecraft").getFriendlyString();
            Path mods = loader.getGameDir().resolve("mods").toAbsolutePath().normalize();
            Map<String, Candidate> candidates = new LinkedHashMap<>();
            for (GuardConfig.Source source : updates.sources()) {
                if (source == null || source.mods() == null || source.mods().isEmpty()) {
                    continue;
                }
                try {
                    select(source, minecraft, mods, candidates);
                } catch (Exception error) {
                    ServerModGuard.LOGGER.warn("Could not read update catalog {}: {}", source.url(), error.toString());
                }
            }
            stage(candidates.values(), loaded);
        } catch (Exception error) {
            ServerModGuard.LOGGER.warn("Mod update check failed", error);
        }
    }

    private void select(GuardConfig.Source source, String minecraft, Path mods, Map<String, Candidate> candidates) throws Exception {
        String url = source.url();
        byte[] raw;
        if (url != null && url.startsWith("local:")) {
            String name = url.substring("local:".length());
            if (!name.matches("[A-Za-z0-9_-]+\\.json")) {
                throw new IOException("Use a JSON filename for a local catalog");
            }
            try (InputStream stream = Files.newInputStream(config.resolve(name))) {
                raw = limitedRead(stream, MAX_CATALOG_BYTES);
            }
        } else {
            raw = get(URI.create(url), CATALOG_HOSTS, MAX_CATALOG_BYTES);
        }
        Catalog catalog = GuardConfig.GSON.fromJson(new String(raw, StandardCharsets.UTF_8), Catalog.class);
        if (catalog == null || catalog.schema() != 1 || catalog.projects() == null) {
            throw new IOException("Unsupported catalog schema");
        }
        var tracked = new HashSet<>(source.mods());
        for (Project project : catalog.projects()) {
            if (project == null || project.id() == null || !tracked.contains(project.id()) || project.id().equals(ServerModGuard.ID)
                || !project.id().matches("[a-z][a-z0-9_-]{1,63}") || project.releases() == null) {
                continue;
            }
            var mod = loader.getModContainer(project.id()).orElse(null);
            if (mod == null || mod.getOrigin().getKind() != ModOrigin.Kind.PATH || mod.getOrigin().getPaths().size() != 1
                || !(mod.getMetadata().getVersion() instanceof SemanticVersion installed)) {
                continue;
            }
            var path = mod.getOrigin().getPaths().getFirst().toAbsolutePath().normalize();
            if (!mods.equals(path.getParent()) || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                || !path.getFileName().toString().matches("[A-Za-z0-9][A-Za-z0-9_.+() -]{0,220}\\.jar")) {
                continue;
            }
            for (Release release : project.releases()) {
                if (release == null || !"fabric".equals(release.loader()) || !Boolean.FALSE.equals(release.prerelease())
                    || release.minecraft() == null || !release.minecraft().contains(minecraft)
                    || release.version() == null || release.url() == null || release.sha256() == null
                    || !release.sha256().matches("[0-9a-f]{64}") || release.bytes() <= 0
                    || release.bytes() > PendingUpdates.MAX_JAR_BYTES) {
                    continue;
                }
                SemanticVersion version;
                try {
                    version = SemanticVersion.parse(release.version());
                } catch (Exception invalid) {
                    continue;
                }
                var previous = candidates.get(project.id());
                if (version.compareTo(installed) > 0 && (previous == null || version.compareTo(previous.version()) > 0)) {
                    candidates.put(project.id(), new Candidate(mod, path, release, version));
                }
            }
        }
    }

    private void stage(Iterable<Candidate> candidates, Map<String, Version> loaded) throws IOException {
        List<Prepared> prepared = new ArrayList<>();
        try {
            for (Candidate candidate : candidates) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    prepared.add(download(candidate));
                } catch (Exception error) {
                    ServerModGuard.LOGGER.warn("Could not prepare update for {}: {}", candidate.mod().getMetadata().getId(), error.toString());
                }
            }

            // Each update must be safe with the currently loaded versions in case an operator applies only part of a batch.
            for (Prepared entry : prepared) {
                var candidate = entry.candidate();
                String id = candidate.mod().getMetadata().getId();
                try {
                    var proposed = new LinkedHashMap<>(loaded);
                    proposed.put(id, entry.version());
                    validateDependencies(entry, proposed, false);
                    validateDependencies(entry, loaded, false);
                    for (var other : prepared) {
                        if (other == entry) {
                            continue;
                        }
                        var pair = new LinkedHashMap<>(proposed);
                        pair.put(other.candidate().mod().getMetadata().getId(), other.version());
                        validateDependencies(entry, pair, true);
                    }
                    PendingUpdates.stage(config, id, candidate.path(), entry.version().getFriendlyString(), candidate.release().sha256(), entry.file());
                    ServerModGuard.LOGGER.info("Staged {} {}. Run --apply after stopping the server.", id, entry.version());
                } catch (Exception error) {
                    ServerModGuard.LOGGER.warn("Skipped update for {}: {}", id, error.toString());
                }
            }
        } finally {
            for (Prepared entry : prepared) {
                Files.deleteIfExists(entry.file());
            }
        }
    }

    private Prepared download(Candidate candidate) throws Exception {
        Release release = candidate.release();
        Path temp = Files.createTempFile(config, "download-", ".tmp");
        boolean ready = false;
        try {
            byte[] raw = get(URI.create(release.url()), DOWNLOAD_HOSTS, release.bytes());
            if (raw.length != release.bytes()) {
                throw new IOException("File size differs from the catalog");
            }
            Files.write(temp, raw);
            if (!PendingUpdates.sha256(temp).equals(release.sha256())) {
                throw new IOException("SHA-256 differs from the catalog");
            }
            try (ZipFile jar = new ZipFile(temp.toFile())) {
                var entry = jar.getEntry("fabric.mod.json");
                if (entry == null || entry.getSize() > MAX_DESCRIPTOR_BYTES) {
                    throw new IOException("Missing or oversized fabric.mod.json");
                }
                try (var stream = jar.getInputStream(entry)) {
                    JsonObject meta = JsonParser.parseString(
                        new String(limitedRead(stream, MAX_DESCRIPTOR_BYTES), StandardCharsets.UTF_8)
                    ).getAsJsonObject();
                    if (!candidate.mod().getMetadata().getId().equals(meta.get("id").getAsString())
                        || (meta.has("environment") && "client".equals(meta.get("environment").getAsString()))) {
                        throw new IOException("JAR has a different mod ID or is client-only");
                    }
                    Version version = SemanticVersion.parse(meta.get("version").getAsString());
                    if (version.compareTo(candidate.version()) != 0) {
                        throw new IOException("JAR version does not match catalog version");
                    }
                    ready = true;
                    return new Prepared(candidate, temp, version, meta);
                }
            }
        } finally {
            if (!ready) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private void validateDependencies(Prepared prepared, Map<String, Version> versions, boolean ownOnly) throws Exception {
        checkPredicates(prepared.descriptor().getAsJsonObject("depends"), versions, true);
        checkPredicates(prepared.descriptor().getAsJsonObject("breaks"), versions, false);
        if (ownOnly) {
            return;
        }
        String id = prepared.candidate().mod().getMetadata().getId();
        for (var loaded : loader.getAllMods()) {
            for (ModDependency dependency : loaded.getMetadata().getDependencies()) {
                if (dependency.getModId().equals(id)
                    && ((dependency.getKind() == ModDependency.Kind.DEPENDS && !dependency.matches(prepared.version()))
                    || (dependency.getKind() == ModDependency.Kind.BREAKS && dependency.matches(prepared.version())))) {
                    throw new IOException("Conflicts with installed " + loaded.getMetadata().getId());
                }
            }
        }
    }

    private void checkPredicates(JsonObject requirements, Map<String, Version> versions, boolean required) throws Exception {
        if (requirements == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : requirements.entrySet()) {
            Version installed = versions.get(entry.getKey());
            List<String> predicates = new ArrayList<>();
            if (entry.getValue().isJsonArray()) {
                for (JsonElement predicate : entry.getValue().getAsJsonArray()) {
                    predicates.add(predicate.getAsString());
                }
            } else {
                predicates.add(entry.getValue().getAsString());
            }
            boolean matches = installed != null && VersionPredicate.parse(predicates).stream().anyMatch(p -> p.test(installed));
            if (matches != required) {
                throw new IOException("Incompatible dependency " + entry.getKey() + " " + predicates);
            }
        }
    }

    private static byte[] get(URI uri, Set<String> hosts, int limit) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null) {
                throw new IOException("Unapproved update URL: " + uri);
            }
            HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", "ServerModGuard/1.0.0");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String destination = connection.getHeaderField("Location");
                    if (destination == null) {
                        throw new IOException("Redirect did not specify a destination");
                    }
                    uri = uri.resolve(destination);
                    continue;
                }
                if (status != 200 || connection.getContentLengthLong() > limit) {
                    throw new IOException("Update request failed or file is too large (HTTP " + status + ")");
                }
                try (InputStream stream = connection.getInputStream()) {
                    return limitedRead(stream, limit);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many update redirects");
    }

    private static byte[] limitedRead(InputStream body, int limit) throws IOException {
        byte[] raw = body.readNBytes(limit + 1);
        if (raw.length > limit) {
            throw new IOException("File exceeded " + limit + " bytes");
        }
        return raw;
    }
}
