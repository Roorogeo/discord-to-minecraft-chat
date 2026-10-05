package com.roorogeo.discordwebhook;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings stored in {@code config/discord-webhook.json}. The file is created with defaults on first launch.
 */
public class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final String FILE_NAME = "discord-webhook.json";

	/** Discord webhook URL, e.g. https://discord.com/api/webhooks/123/abc. Leave empty to disable the mod. */
	public String webhookUrl = "";

	/** Name shown on Discord for server events (joins, deaths, advancements, start/stop). */
	public String serverName = "Minecraft Server";

	/** Avatar for server events. Leave empty to use the webhook's own avatar. */
	public String serverAvatarUrl = "";

	/** Avatar for chat messages. {uuid} and {name} are replaced with the player's UUID and name. */
	public String playerAvatarUrl = "https://mc-heads.net/avatar/{uuid}/128";

	public boolean sendChat = true;
	public boolean sendJoinLeave = true;
	public boolean sendDeaths = true;
	public boolean sendAdvancements = true;
	public boolean sendServerStartStop = true;

	public static ModConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		ModConfig config = null;

		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				config = GSON.fromJson(reader, ModConfig.class);
			} catch (IOException | JsonParseException e) {
				DiscordWebhookMod.LOGGER.error("Failed to read {}, using defaults", path, e);
			}
		}

		if (config == null) {
			config = new ModConfig();
		}

		// Write back so newly added options show up in existing files.
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(config, writer);
		} catch (IOException e) {
			DiscordWebhookMod.LOGGER.error("Failed to write {}", path, e);
		}

		return config;
	}
}
