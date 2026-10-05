package com.roorogeo.discordwebhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A minimal Discord bot that connects to the gateway and receives slash commands. It only needs the bot
 * token, no privileged intents, since slash commands are delivered to bots regardless of intents.
 */
public class DiscordBot {
	private static final String API = "https://discord.com/api/v10";
	private static final String GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json";
	private static final int EPHEMERAL = 1 << 6;

	/** A slash command someone used in Discord. Call {@link #reply} once with the answer. */
	public interface SlashCommand {
		String name();

		String userId();

		String guildId();

		String option(String name);

		void reply(String content);
	}

	public interface SlashCommandHandler {
		void handle(SlashCommand command);
	}

	private final String token;
	private final String guildId;
	private final SlashCommandHandler handler;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Discord Bot");
		thread.setDaemon(true);
		return thread;
	});

	private volatile WebSocket socket;
	/** WebSocket only allows one outgoing message at a time, so sends are chained. */
	private CompletableFuture<?> sendQueue = CompletableFuture.completedFuture(null);
	private volatile ScheduledFuture<?> heartbeat;
	private volatile Integer sequence;
	private volatile String applicationId;
	private volatile boolean heartbeatAcknowledged = true;
	private volatile boolean running;

	public DiscordBot(String token, String guildId, SlashCommandHandler handler) {
		this.token = token;
		this.guildId = guildId == null || guildId.isBlank() ? null : guildId.trim();
		this.handler = handler;
	}

	public void start() {
		running = true;
		connect();
	}

	public void stop() {
		running = false;
		stopHeartbeat();
		WebSocket current = socket;

		if (current != null) {
			current.sendClose(WebSocket.NORMAL_CLOSURE, "Server stopping");
		}

		scheduler.shutdownNow();
	}

	private void connect() {
		if (!running) {
			return;
		}

		http.newWebSocketBuilder()
				.buildAsync(URI.create(GATEWAY), new Listener())
				.whenComplete((ws, error) -> {
					if (error != null) {
						DiscordWebhookMod.LOGGER.warn("Could not connect to Discord: {}", error.toString());
						scheduleConnect();
					}
				});
	}

	/** Reconnects after {@code ws} was lost. Ignored if {@code ws} was already replaced, so a dead connection only reconnects once. */
	private synchronized void reconnectLater(WebSocket ws) {
		if (socket != ws) {
			return;
		}

		socket = null;
		stopHeartbeat();
		scheduleConnect();
	}

	private void scheduleConnect() {
		if (running && !scheduler.isShutdown()) {
			scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
		}
	}

	private synchronized void send(WebSocket ws, String text) {
		sendQueue = sendQueue
				.handle((result, error) -> null)
				.thenCompose(ignored -> ws.sendText(text, true));
	}

	private void stopHeartbeat() {
		ScheduledFuture<?> current = heartbeat;

		if (current != null) {
			current.cancel(false);
		}
	}

	private void onPayload(WebSocket ws, JsonObject payload) {
		int op = payload.get("op").getAsInt();

		if (payload.has("s") && !payload.get("s").isJsonNull()) {
			sequence = payload.get("s").getAsInt();
		}

		switch (op) {
			case 10 -> { // Hello
				long interval = payload.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
				heartbeatAcknowledged = true;
				stopHeartbeat();
				heartbeat = scheduler.scheduleAtFixedRate(() -> sendHeartbeat(ws), (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);
				identify(ws);
			}
			case 11 -> heartbeatAcknowledged = true;
			case 1 -> sendHeartbeat(ws);
			case 7, 9 -> { // Reconnect / invalid session: start over.
				ws.abort();
				reconnectLater(ws);
			}
			case 0 -> onDispatch(payload.get("t").getAsString(), payload.getAsJsonObject("d"));
			default -> { }
		}
	}

	private void sendHeartbeat(WebSocket ws) {
		if (!heartbeatAcknowledged) {
			// The connection is dead (zombie): drop it and reconnect.
			ws.abort();
			reconnectLater(ws);
			return;
		}

		heartbeatAcknowledged = false;
		JsonObject payload = new JsonObject();
		payload.addProperty("op", 1);
		payload.addProperty("d", sequence);
		send(ws, payload.toString());
	}

	private void identify(WebSocket ws) {
		JsonObject properties = new JsonObject();
		properties.addProperty("os", System.getProperty("os.name"));
		properties.addProperty("browser", "discord-webhook-chat");
		properties.addProperty("device", "discord-webhook-chat");

		JsonObject data = new JsonObject();
		data.addProperty("token", token);
		data.addProperty("intents", 0);
		data.add("properties", properties);

		JsonObject payload = new JsonObject();
		payload.addProperty("op", 2);
		payload.add("d", data);
		send(ws, payload.toString());
	}

	private void onDispatch(String type, JsonObject data) {
		switch (type) {
			case "READY" -> {
				applicationId = data.getAsJsonObject("application").get("id").getAsString();
				DiscordWebhookMod.LOGGER.info("Connected to Discord as {}", data.getAsJsonObject("user").get("username").getAsString());
				registerCommands();
			}
			case "INTERACTION_CREATE" -> onInteraction(data);
			default -> { }
		}
	}

	private void registerCommands() {
		JsonArray commands = new JsonArray();
		commands.add(command("link", "Link your Discord account to your Minecraft account",
				option("code", "The code from running /discord link in Minecraft")));
		commands.add(command("unlink", "Unlink your Discord account from your Minecraft account"));
		commands.add(command("mc", "Run a Minecraft command as your linked player",
				option("command", "The command to run, without the leading /")));

		String path = guildId != null
				? "/applications/" + applicationId + "/guilds/" + guildId + "/commands"
				: "/applications/" + applicationId + "/commands";

		request("PUT", path, commands.toString()).thenAccept(response -> {
			if (response.statusCode() >= 300) {
				DiscordWebhookMod.LOGGER.warn("Failed to register Discord slash commands: HTTP {} {}", response.statusCode(), response.body());
			}
		});
	}

	private static JsonObject command(String name, String description, JsonObject... options) {
		JsonObject command = new JsonObject();
		command.addProperty("name", name);
		command.addProperty("description", description);
		command.addProperty("type", 1);

		// Only usable inside servers, not in DMs with the bot.
		JsonArray contexts = new JsonArray();
		contexts.add(0);
		command.add("contexts", contexts);

		JsonArray optionArray = new JsonArray();

		for (JsonObject option : options) {
			optionArray.add(option);
		}

		command.add("options", optionArray);
		return command;
	}

	private static JsonObject option(String name, String description) {
		JsonObject option = new JsonObject();
		option.addProperty("name", name);
		option.addProperty("description", description);
		option.addProperty("type", 3); // String
		option.addProperty("required", true);
		return option;
	}

	private void onInteraction(JsonObject data) {
		if (data.get("type").getAsInt() != 2) { // Only slash commands.
			return;
		}

		String interactionId = data.get("id").getAsString();
		String interactionToken = data.get("token").getAsString();
		String interactionGuild = data.has("guild_id") ? data.get("guild_id").getAsString() : null;
		JsonObject member = data.getAsJsonObject("member");
		JsonObject user = member != null ? member.getAsJsonObject("user") : data.getAsJsonObject("user");
		String userId = user.get("id").getAsString();
		JsonObject commandData = data.getAsJsonObject("data");
		String name = commandData.get("name").getAsString();

		Map<String, String> options = new HashMap<>();

		if (commandData.has("options")) {
			for (JsonElement element : commandData.getAsJsonArray("options")) {
				JsonObject option = element.getAsJsonObject();
				options.put(option.get("name").getAsString(), option.get("value").getAsString());
			}
		}

		// Acknowledge right away (Discord allows 3 seconds), then fill in the answer when it's ready.
		JsonObject deferData = new JsonObject();
		deferData.addProperty("flags", EPHEMERAL);
		JsonObject defer = new JsonObject();
		defer.addProperty("type", 5);
		defer.add("data", deferData);

		CompletableFuture<?> deferred = request("POST", "/interactions/" + interactionId + "/" + interactionToken + "/callback", defer.toString());

		handler.handle(new SlashCommand() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public String userId() {
				return userId;
			}

			@Override
			public String guildId() {
				return interactionGuild;
			}

			@Override
			public String option(String optionName) {
				return options.get(optionName);
			}

			@Override
			public void reply(String content) {
				JsonObject message = new JsonObject();
				message.addProperty("content", content.length() <= 2000 ? content : content.substring(0, 1999) + "…");
				JsonObject allowedMentions = new JsonObject();
				allowedMentions.add("parse", new JsonArray());
				message.add("allowed_mentions", allowedMentions);

				deferred.thenRun(() -> request("PATCH", "/webhooks/" + applicationId + "/" + interactionToken + "/messages/@original", message.toString()));
			}
		});
	}

	private CompletableFuture<HttpResponse<String>> request(String method, String path, String body) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bot " + token)
				.header("Content-Type", "application/json")
				.header("User-Agent", "DiscordBot (https://github.com/roorogeo/discord-to-minecraft-chat, 1.0)")
				.method(method, HttpRequest.BodyPublishers.ofString(body))
				.build();

		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
			if (error != null) {
				DiscordWebhookMod.LOGGER.warn("Discord API request {} {} failed: {}", method, path.replace(token, "***"), error.toString());
			} else if (response.statusCode() >= 400) {
				DiscordWebhookMod.LOGGER.warn("Discord API request {} returned HTTP {}: {}", method, response.statusCode(), response.body());
			}
		});
	}

	private class Listener implements WebSocket.Listener {
		private final StringBuilder buffer = new StringBuilder();

		@Override
		public void onOpen(WebSocket ws) {
			synchronized (DiscordBot.this) {
				socket = ws;
				sendQueue = CompletableFuture.completedFuture(null);
			}

			ws.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			buffer.append(data);

			if (last) {
				String text = buffer.toString();
				buffer.setLength(0);

				try {
					onPayload(ws, JsonParser.parseString(text).getAsJsonObject());
				} catch (RuntimeException e) {
					DiscordWebhookMod.LOGGER.warn("Failed to handle Discord gateway message", e);
				}
			}

			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
			if (running) {
				if (statusCode == 4004) {
					DiscordWebhookMod.LOGGER.error("Discord rejected the bot token. Check botToken in config/discord-webhook.json.");
					running = false;
					stopHeartbeat();
					return null;
				}

				DiscordWebhookMod.LOGGER.info("Discord connection closed ({} {}), reconnecting", statusCode, reason);
				reconnectLater(ws);
			}

			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			DiscordWebhookMod.LOGGER.warn("Discord connection error: {}", error.toString());
			reconnectLater(ws);
		}
	}
}
