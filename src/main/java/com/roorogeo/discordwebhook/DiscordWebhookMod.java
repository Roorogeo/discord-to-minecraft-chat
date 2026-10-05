package com.roorogeo.discordwebhook;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

public class DiscordWebhookMod implements ModInitializer {
	public static final String MOD_ID = "discordwebhook";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final int COLOR_JOIN = 0x55FF55;
	private static final int COLOR_LEAVE = 0xFF5555;
	private static final int COLOR_DEATH = 0x2B2D31;
	private static final int COLOR_ADVANCEMENT = 0xFFAA00;
	private static final int COLOR_CHALLENGE = 0xAA00AA;
	private static final int COLOR_SERVER = 0x5865F2;

	private ModConfig config;
	// Created per server start, so singleplayer worlds can be opened and closed repeatedly.
	private volatile WebhookClient webhook;

	@Override
	public void onInitialize() {
		config = ModConfig.load();

		if (config.botToken != null && !config.botToken.isBlank()) {
			new DiscordCommands(config).register();
			LOGGER.info("Discord bot enabled");
		}

		if (config.webhookUrl == null || config.webhookUrl.isBlank()) {
			LOGGER.warn("No Discord webhook URL set. Add one to config/discord-webhook.json and restart the server.");
			return;
		}

		if (!config.webhookUrl.trim().startsWith("https://")) {
			LOGGER.error("Invalid Discord webhook URL in config/discord-webhook.json, it should start with https://");
			return;
		}

		ServerLifecycleEvents.SERVER_STARTING.register(server -> webhook = new WebhookClient(config.webhookUrl.trim()));

		// Chat messages typed by players.
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, boundChatType) -> {
			if (config.sendChat) {
				sendChat(sender.getName().getString(), sender.getUUID(), message.decoratedContent().getString());
			}
		});

		// /say and /me, sent by players or the console.
		ServerMessageEvents.COMMAND_MESSAGE.register((message, source, boundChatType) -> {
			if (config.sendChat) {
				UUID uuid = source.getEntity() != null ? source.getEntity().getUUID() : null;
				sendChat(source.getTextName(), uuid, message.decoratedContent().getString());
			}
		});

		// Joins, leaves, deaths and advancements are all broadcast by vanilla as system messages, which
		// also means vanilla game rules like showDeathMessages and announceAdvancements are respected.
		ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
			if (!overlay) {
				onSystemMessage(message);
			}
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (config.sendServerStartStop) {
				sendEvent(":white_check_mark: **Server started**", COLOR_SERVER);
			}
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (config.sendServerStartStop) {
				sendEvent(":octagonal_sign: **Server stopped**", COLOR_SERVER);
			}
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);

		LOGGER.info("Discord webhook relay enabled");
	}

	private void onSystemMessage(Component message) {
		if (!(message.getContents() instanceof TranslatableContents translatable)) {
			return;
		}

		String key = translatable.getKey();
		String text = message.getString();

		if (key.startsWith("multiplayer.player.joined")) {
			if (config.sendJoinLeave) {
				sendEvent(":inbox_tray: **" + escape(text) + "**", COLOR_JOIN);
			}
		} else if (key.equals("multiplayer.player.left")) {
			if (config.sendJoinLeave) {
				sendEvent(":outbox_tray: **" + escape(text) + "**", COLOR_LEAVE);
			}
		} else if (key.startsWith("death.")) {
			if (config.sendDeaths) {
				sendEvent(":skull: " + escape(text), COLOR_DEATH);
			}
		} else if (key.startsWith("chat.type.advancement.")) {
			if (config.sendAdvancements) {
				boolean challenge = key.endsWith(".challenge");
				sendEvent((challenge ? ":trophy: " : ":medal: ") + escape(text), challenge ? COLOR_CHALLENGE : COLOR_ADVANCEMENT);
			}
		}
	}

	private void onServerStopped(MinecraftServer server) {
		WebhookClient client = webhook;
		webhook = null;

		if (client != null) {
			client.shutdown();
		}
	}

	private void sendChat(String name, UUID uuid, String content) {
		WebhookClient client = webhook;

		if (client == null) {
			return;
		}

		String avatar = config.playerAvatarUrl == null ? "" : config.playerAvatarUrl
				.replace("{uuid}", uuid != null ? uuid.toString() : name)
				.replace("{name}", name);
		client.sendMessage(name, avatar, escape(content));
	}

	private void sendEvent(String description, int color) {
		WebhookClient client = webhook;

		if (client != null) {
			client.sendEmbed(config.serverName, config.serverAvatarUrl, description, color);
		}
	}

	/** Escapes Discord markdown so player names like _cool_guy_ or chat like **hi** show up as typed. */
	private static String escape(String text) {
		return text
				.replaceAll("([\\\\*_~`|\\[\\]])", "\\\\$1")
				.replaceAll("(?m)^([#>-])", "\\\\$1");
	}
}
