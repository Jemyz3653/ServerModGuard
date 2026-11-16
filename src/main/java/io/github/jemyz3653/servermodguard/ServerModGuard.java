package io.github.jemyz3653.servermodguard;

import io.github.jemyz3653.servermodguard.mixin.ClientInformationAccessor;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerModGuard implements DedicatedServerModInitializer {
    static final String ID = "servermodguard";
    static final Logger LOGGER = LoggerFactory.getLogger(ID);
    private static final String FALLBACK = "Update your client mods and reconnect. The server requires these versions:";

    private record ClientCheck(String name, String version, Identifier channel) {}

    @Override
    public void onInitializeServer() {
        FabricLoader loader = FabricLoader.getInstance();
        try {
            var configDir = loader.getConfigDir().resolve(ID);
            GuardConfig config = GuardConfig.load(configDir.resolve("config.json"));
            installClientChecks(loader, config);
            if (config.updates().enabled()) {
                var updater = new ModUpdater(loader, configDir, config.updates());
                ServerLifecycleEvents.SERVER_STARTED.register(server -> updater.start());
                ServerLifecycleEvents.SERVER_STOPPING.register(server -> updater.stop());
            }
        } catch (Exception error) {
            LOGGER.error("Could not initialize Server Mod Guard. Check config/servermodguard/config.json", error);
        }
    }

    private void installClientChecks(FabricLoader loader, GuardConfig config) {
        var checks = new ArrayList<ClientCheck>();
        var ids = new HashSet<String>();
        for (GuardConfig.Rule rule : config.clientChecks()) {
            if (rule == null || rule.mod() == null || rule.channels() == null || !ids.add(rule.mod())) {
                throw new IllegalArgumentException("Invalid or repeated client check");
            }
            var mod = loader.getModContainer(rule.mod()).orElseThrow(
                () -> new IllegalArgumentException("Client check names a mod not installed on the server: " + rule.mod())
            );
            var version = mod.getMetadata().getVersion().getFriendlyString();
            var channel = rule.channels().get(version);
            if (channel == null) {
                throw new IllegalArgumentException("No client channel configured for " + rule.mod() + " " + version);
            }
            checks.add(new ClientCheck(mod.getMetadata().getName(), version, Identifier.parse(channel)));
        }
        if (checks.isEmpty()) {
            return;
        }

        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            var missing = checks.stream().filter(check -> !ServerConfigurationNetworking.canSend(handler, check.channel())).toList();
            if (missing.isEmpty()) {
                return;
            }
            var information = ((ClientInformationAccessor)handler).servermodguard$getClientInformation();
            var lang = information == null ? "en_us" : information.language();
            var message = Component.literal(localized(lang)).withStyle(ChatFormatting.YELLOW);
            for (var check : missing) {
                message.append(Component.literal("\n• " + check.name() + " " + check.version()).withStyle(ChatFormatting.WHITE));
            }
            handler.disconnect(message);
        });
    }

    private static String localized(String language) {
        String locale = language == null ? "en_us" : language.toLowerCase(Locale.ROOT);
        if (!locale.matches("[a-z]{2}_[a-z]{2}")) {
            return FALLBACK;
        }
        try (var stream = ServerModGuard.class.getResourceAsStream("/servermodguard/lang/" + locale + ".json")) {
            if (stream == null) {
                return FALLBACK;
            }
            var strings = GuardConfig.GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), Map.class);
            return strings.get("update_required") instanceof String text ? text : FALLBACK;
        } catch (Exception error) {
            LOGGER.warn("Could not load client language {}", locale, error);
            return FALLBACK;
        }
    }
}
