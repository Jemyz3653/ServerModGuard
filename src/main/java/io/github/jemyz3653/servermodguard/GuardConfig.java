package io.github.jemyz3653.servermodguard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record GuardConfig(List<Rule> clientChecks, Updates updates) {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    record Rule(String mod, Map<String, String> channels) {}
    record Source(String url, List<String> mods) {}
    record Updates(boolean enabled, int checkMinutes, List<Source> sources) {}

    static GuardConfig load(Path location) throws IOException {
        if (Files.notExists(location)) {
            Files.createDirectories(location.getParent());
            var example = new GuardConfig(
                List.of(),
                new Updates(false, 60, List.of(new Source(
                    "https://raw.githubusercontent.com/Jemyz3653/Prism-GitHub-Mods/main/mods.json", List.of()
                )))
            );
            Files.writeString(location, GSON.toJson(example) + "\n");
            return example;
        }
        GuardConfig config = GSON.fromJson(Files.readString(location), GuardConfig.class);
        if (config == null || config.clientChecks == null || config.updates == null || config.updates.sources == null
            || config.updates.checkMinutes < 1 || config.updates.checkMinutes > 1440) {
            throw new IOException("Invalid Server Mod Guard configuration");
        }
        return config;
    }
}
