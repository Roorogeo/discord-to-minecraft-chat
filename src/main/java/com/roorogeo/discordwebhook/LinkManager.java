package com.roorogeo.discordwebhook;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Keeps track of which Discord user is linked to which Minecraft player, stored in
 * {@code config/discord-links.json}, plus the short-lived codes used to create a link.
 */
public class LinkManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type LINKS_TYPE = new TypeToken<Map<String, LinkedPlayer>>() { }.getType();
	private static final long CODE_LIFETIME_MILLIS = 5 * 60 * 1000;
	/** Wrong codes allowed per Discord user per window, so codes can't be guessed by brute force. */
	private static final int MAX_FAILED_ATTEMPTS = 5;
	private static final long FAILED_ATTEMPT_WINDOW_MILLIS = 15 * 60 * 1000;

	public record LinkedPlayer(UUID uuid, String name) { }

	private record PendingCode(UUID uuid, String name, long expiresAt) { }

	private record FailedAttempts(int count, long windowStart) { }

	/** Result of {@link #redeemCode}: the linked player, or why linking failed. */
	public sealed interface RedeemResult {
		record Linked(LinkedPlayer player) implements RedeemResult { }

		record InvalidCode() implements RedeemResult { }

		record TooManyAttempts() implements RedeemResult { }
	}

	private final Path path = FabricLoader.getInstance().getConfigDir().resolve("discord-links.json");
	private final SecureRandom random = new SecureRandom();
	/** Discord user ID → Minecraft player. */
	private final Map<String, LinkedPlayer> links = new ConcurrentHashMap<>();
	/** Link code → player who asked for it. */
	private final Map<String, PendingCode> pendingCodes = new ConcurrentHashMap<>();
	/** Discord user ID → recent wrong codes. */
	private final Map<String, FailedAttempts> failedAttempts = new ConcurrentHashMap<>();

	public LinkManager() {
		load();
	}

	/** Creates a new code for a player to type into Discord, replacing any code they had before. */
	public String createCode(UUID uuid, String name) {
		pendingCodes.values().removeIf(code -> code.uuid().equals(uuid) || code.expiresAt() < System.currentTimeMillis());

		String code;

		do {
			code = String.format("%06d", random.nextInt(1_000_000));
		} while (pendingCodes.containsKey(code));

		pendingCodes.put(code, new PendingCode(uuid, name, System.currentTimeMillis() + CODE_LIFETIME_MILLIS));
		return code;
	}

	/** Links the Discord user to the player who created {@code code}. */
	public synchronized RedeemResult redeemCode(String discordId, String code) {
		long now = System.currentTimeMillis();
		FailedAttempts failed = failedAttempts.get(discordId);

		if (failed != null && now - failed.windowStart() > FAILED_ATTEMPT_WINDOW_MILLIS) {
			failedAttempts.remove(discordId);
			failed = null;
		}

		if (failed != null && failed.count() >= MAX_FAILED_ATTEMPTS) {
			return new RedeemResult.TooManyAttempts();
		}

		PendingCode pending = pendingCodes.remove(code.trim());

		if (pending == null || pending.expiresAt() < now) {
			failedAttempts.put(discordId, failed == null ? new FailedAttempts(1, now) : new FailedAttempts(failed.count() + 1, failed.windowStart()));
			return new RedeemResult.InvalidCode();
		}

		failedAttempts.remove(discordId);

		// A Minecraft account can only be linked to one Discord account at a time.
		links.values().removeIf(player -> player.uuid().equals(pending.uuid()));
		LinkedPlayer player = new LinkedPlayer(pending.uuid(), pending.name());
		links.put(discordId, player);
		save();
		return new RedeemResult.Linked(player);
	}

	public LinkedPlayer get(String discordId) {
		return links.get(discordId);
	}

	public LinkedPlayer unlinkDiscord(String discordId) {
		LinkedPlayer removed = links.remove(discordId);

		if (removed != null) {
			save();
		}

		return removed;
	}

	public boolean unlinkPlayer(UUID uuid) {
		boolean removed = false;

		for (Iterator<LinkedPlayer> it = links.values().iterator(); it.hasNext(); ) {
			if (it.next().uuid().equals(uuid)) {
				it.remove();
				removed = true;
			}
		}

		if (removed) {
			save();
		}

		return removed;
	}

	private void load() {
		if (!Files.exists(path)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			Map<String, LinkedPlayer> loaded = GSON.fromJson(reader, LINKS_TYPE);

			if (loaded != null) {
				links.putAll(loaded);
			}
		} catch (IOException | JsonParseException e) {
			DiscordWebhookMod.LOGGER.error("Failed to read {}", path, e);
		}
	}

	private synchronized void save() {
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(new HashMap<>(links), LINKS_TYPE, writer);
		} catch (IOException e) {
			DiscordWebhookMod.LOGGER.error("Failed to write {}", path, e);
		}
	}
}
