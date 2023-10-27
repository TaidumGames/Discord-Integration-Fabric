package de.erdbeerbaerlp.dcintegration.fabric.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dcshadow.org.json.JSONObject;
import de.erdbeerbaerlp.dcintegration.common.DiscordIntegration;
import de.erdbeerbaerlp.dcintegration.common.WorkThread;
import de.erdbeerbaerlp.dcintegration.common.storage.Configuration;
import de.erdbeerbaerlp.dcintegration.common.storage.Localization;
import de.erdbeerbaerlp.dcintegration.common.storage.linking.LinkManager;
import de.erdbeerbaerlp.dcintegration.common.util.DiscordMessage;
import de.erdbeerbaerlp.dcintegration.common.util.TextColors;
import de.erdbeerbaerlp.dcintegration.fabric.util.FabricMessageUtils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Path ROLE_ACTIONS_PATH = Paths.get("config", "Discord-Integration-Roles.json");


    /**
     * Handle whitelisting
     */
    @Inject(method = "checkCanJoin", at = @At("HEAD"), cancellable = true)
    public void canJoin(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Text> cir) {
        if (DiscordIntegration.INSTANCE == null) return;
        LinkManager.checkGlobalAPI(profile.getId());
        if (Configuration.instance().linking.whitelistMode && DiscordIntegration.INSTANCE.getServerInterface().isOnlineMode()) {
            try {
                if (!LinkManager.isPlayerLinked(profile.getId())) {
                    cir.setReturnValue(Text.of(Localization.instance().linking.notWhitelistedCode.replace("%code%", "" + LinkManager.genLinkNumber(profile.getId()))));
                } else if (!DiscordIntegration.INSTANCE.canPlayerJoin(profile.getId())) {
                    cir.setReturnValue(Text.of(Localization.instance().linking.notWhitelistedRole));
                }
            } catch (IllegalStateException e) {
                cir.setReturnValue(Text.of("An error occured\nPlease check Server Log for more information\n\n" + e));
                e.printStackTrace();
            }
        }
    }

    @Inject(at = @At(value = "TAIL"), method = "onPlayerConnect")
    private void onPlayerJoin(ClientConnection conn, ServerPlayerEntity p, CallbackInfo ci) {
        if (DiscordIntegration.INSTANCE != null) {
            if (LinkManager.isPlayerLinked(p.getUuid()) && LinkManager.getLink(null, p.getUuid()).settings.hideFromDiscord)
                return;
            LinkManager.checkGlobalAPI(p.getUuid());
            if (!Localization.instance().playerJoin.isBlank()) {
                if (Configuration.instance().embedMode.enabled && Configuration.instance().embedMode.playerJoinMessage.asEmbed) {
                    final String avatarURL = Configuration.instance().webhook.playerAvatarURL.replace("%uuid%", p.getUuid().toString()).replace("%uuid_dashless%", p.getUuid().toString().replace("-", "")).replace("%name%", p.getName().getString()).replace("%randomUUID%", UUID.randomUUID().toString());
                    if (!Configuration.instance().embedMode.playerJoinMessage.customJSON.isBlank()) {
                        final EmbedBuilder b = Configuration.instance().embedMode.playerJoinMessage.toEmbedJson(Configuration.instance().embedMode.playerJoinMessage.customJSON
                                .replace("%uuid%", p.getUuid().toString())
                                .replace("%uuid_dashless%", p.getUuid().toString().replace("-", ""))
                                .replace("%name%", FabricMessageUtils.formatPlayerName(p))
                                .replace("%randomUUID%", UUID.randomUUID().toString())
                                .replace("%avatarURL%", avatarURL)
                                .replace("%playerColor%", "" + TextColors.generateFromUUID(p.getUuid()).getRGB())
                        );
                        DiscordIntegration.INSTANCE.sendMessage(new DiscordMessage(b.build()));
                    } else {
                        final EmbedBuilder b = Configuration.instance().embedMode.playerJoinMessage.toEmbed();
                        b.setAuthor(FabricMessageUtils.formatPlayerName(p), null, avatarURL)
                                .setDescription(Localization.instance().playerJoin.replace("%player%", FabricMessageUtils.formatPlayerName(p)));
                        DiscordIntegration.INSTANCE.sendMessage(new DiscordMessage(b.build()));
                    }
                } else
                    DiscordIntegration.INSTANCE.sendMessage(Localization.instance().playerJoin.replace("%player%", FabricMessageUtils.formatPlayerName(p)));
            }


            // Fix link status (if user does not have role, give the role to the user, or vice versa)
            WorkThread.executeJob(() -> {
                final UUID uuid = p.getUuid();
                if (!LinkManager.isPlayerLinked(uuid)) return;
                final Member member = DiscordIntegration.INSTANCE.getMemberById(LinkManager.getLink(null, uuid).discordID);

                Map<String, String> roleActions = loadRoleActionsFromJSON();
                for (Map.Entry<String, String> entry : roleActions.entrySet()) {
                    String roleId = entry.getKey();
                    String action = entry.getValue();
                    if (memberHasRole(member, roleId)) {
                        executeMinecraftCommand(p, "lp user " + p.getName().getString() + " parent add " + action);
                    } else {
                        executeMinecraftCommand(p, "lp user " + p.getName().getString() + " parent remove " + action);
                    }
                }
            });
        }
    }

    private boolean memberHasRole(Member member, String roleId) {
        for (Role role : member.getRoles()) {
            if (role.getId().equals(roleId)) {
                return true;
            }
        }
        return false;
    }

    private void executeMinecraftCommand(ServerPlayerEntity p, String cmd) {
        MinecraftServer server = p.getServer();
        ServerCommandSource consoleSource = server.getCommandSource().withLevel(4); // Level 4 typically means server console permissions
        CommandManager commandManager = server.getCommandManager();
        ParseResults<ServerCommandSource> parsed = commandManager.getDispatcher().parse(cmd, consoleSource);
        try {
            commandManager.getDispatcher().execute(parsed);
        } catch (CommandSyntaxException e) {
            // Handle command execution failure, if necessary
            LOGGER.error("Unable to execute command: " + cmd);
            e.printStackTrace();
        }
    }

    private Map<String, String> loadRoleActionsFromJSON() {
        Map<String, String> roleActions = new HashMap<>();

        try {
            if (!Files.exists(ROLE_ACTIONS_PATH.getParent())) {
                Files.createDirectory(ROLE_ACTIONS_PATH.getParent());
            }
            // Check if the file exists
            if (!Files.exists(ROLE_ACTIONS_PATH)) {
                // Create a default JSON content
                JSONObject defaultJson = new JSONObject();
                JSONObject roles = new JSONObject();
                roles.put("0000000000000000000", "syncroleexample");
                roles.put("0000000000000000001", "luckpermsgroupnamehere");
                defaultJson.put("roles", roles);

                // Use 4 spaces for indentation for pretty-printing
                String prettyPrintedJson = defaultJson.toString(4);

                Files.write(ROLE_ACTIONS_PATH, prettyPrintedJson.getBytes());
                LOGGER.info("Discord-Integration-Roles.json did not exist and has been created with default values.");
            }

            // Load the .json file
            String content = new String(Files.readAllBytes(ROLE_ACTIONS_PATH));

            // Parse the JSON content
            JSONObject json = new JSONObject(content);
            JSONObject roles = json.getJSONObject("roles");

            // Populate the map
            for (String key : roles.keySet()) {
                roleActions.put(key, roles.getString(key));
            }

        } catch (Exception e) {
            LOGGER.error("Error handling roleActions.json", e);
        }

        return roleActions;

    }

}
