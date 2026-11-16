package io.github.jemyz3653.servermodguard.mixin;

import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public interface ClientInformationAccessor {
    @Accessor("clientInformation")
    ClientInformation servermodguard$getClientInformation();
}
