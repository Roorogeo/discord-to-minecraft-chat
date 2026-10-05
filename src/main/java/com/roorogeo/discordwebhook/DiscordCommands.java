package com.roorogeo.discordwebhook;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Lets players link their Discord account with {@code /discord link} in game and {@code /link} in Discord,
 * then run Minecraft commands from Discord with {@code /mc}. Commands run as the linked player, with that
 * player's own permission level, so linking never gives anyone more power than they have in game.
 */
public class DiscordCommands {
	private final ModConfig config;
	private final LinkManager links = new LinkManager();
	private volatile MinecraftServer server;
	private volatile DiscordBot bot;

	public DiscordCommands(ModConfig config) {
		this.config = config;
	}

	public void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("discord")
						.then(Commands.literal("link").executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							String code = links.createCode(player.getUUID(), player.getName().getString());
							context.getSource().sendSuccess(() -> Component.literal("Run ")
									.append(Component.literal("/link " + code).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
									.append(" in Discord within 5 minutes to link your account."), false);
							return 1;
						}))
						.then(Commands.literal("unlink").executes(context -> {
							ServerPlayer player = context.getSource().getPlayerOrException();
							boolean unlinked = links.unlinkPlayer(player.getUUID());
							context.getSource().sendSuccess(() -> Component.literal(unlinked
									? "Your Discord account has been unlinked."
									: "Your account isn't linked to Discord."), false);
							return unlinked ? 1 : 0;
						}))));

		ServerLifecycleEvents.SERVER_STARTED.register(started -> {
			server = started;
			bot = new DiscordBot(config.botToken.trim(), config.guildId, this::onSlashCommand);
			bot.start();
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(stopping -> {
			DiscordBot current = bot;
			bot = null;
			server = null;

			if (current != null) {
				current.stop();
			}
		});
	}

	private void onSlashCommand(DiscordBot.SlashCommand command) {
		String guildId = config.guildId == null ? "" : config.guildId.trim();

		if (!guildId.isEmpty() && !guildId.equals(command.guildId())) {
			command.reply("This bot only accepts commands from its own Discord server.");
			return;
		}

		switch (command.name()) {
			case "link" -> {
				switch (links.redeemCode(command.userId(), command.option("code"))) {
					case LinkManager.RedeemResult.Linked(LinkManager.LinkedPlayer player) -> {
						command.reply("Linked to Minecraft account **" + player.name() + "**. You can now use `/mc`.");
						notifyPlayer(player, "Your account is now linked to Discord.");
					}
					case LinkManager.RedeemResult.InvalidCode() ->
							command.reply("That code is invalid or has expired. Run `/discord link` in Minecraft to get a new one.");
					case LinkManager.RedeemResult.TooManyAttempts() ->
							command.reply("Too many wrong codes. Try again in 15 minutes.");
				}
			}
			case "unlink" -> {
				LinkManager.LinkedPlayer player = links.unlinkDiscord(command.userId());
				command.reply(player != null
						? "Unlinked from Minecraft account **" + player.name() + "**."
						: "Your Discord account isn't linked.");
			}
			case "mc" -> runCommand(command);
			default -> command.reply("Unknown command.");
		}
	}

	/** Tells the player in game, if they're online, so they notice if someone else linked their account. */
	private void notifyPlayer(LinkManager.LinkedPlayer linked, String message) {
		MinecraftServer current = server;

		if (current != null) {
			current.execute(() -> {
				ServerPlayer player = current.getPlayerList().getPlayer(linked.uuid());

				if (player != null) {
					player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
				}
			});
		}
	}

	private void runCommand(DiscordBot.SlashCommand command) {
		LinkManager.LinkedPlayer player = links.get(command.userId());
		MinecraftServer current = server;

		if (player == null) {
			command.reply("Link your account first: run `/discord link` in Minecraft, then `/link <code>` here.");
			return;
		}

		if (current == null) {
			command.reply("The server isn't running.");
			return;
		}

		String text = command.option("command").trim();

		if (text.startsWith("/")) {
			text = text.substring(1);
		}

		String commandText = text;

		current.execute(() -> {
			OutputCapture output = new OutputCapture();
			DiscordWebhookMod.LOGGER.info("{} ran a command from Discord: /{}", player.name(), commandText);
			current.getCommands().performPrefixedCommand(createSource(current, player, output), commandText);
			command.reply(output.toReply(commandText));
		});
	}

	private static CommandSourceStack createSource(MinecraftServer server, LinkManager.LinkedPlayer linked, CommandSource output) {
		ServerPlayer online = server.getPlayerList().getPlayer(linked.uuid());

		if (online != null) {
			return online.createCommandSourceStack().withSource(output);
		}

		// Offline: run at world spawn with the permission level the player would have if they were online.
		return new CommandSourceStack(output, Vec3.ZERO, Vec2.ZERO, server.overworld(),
				server.getProfilePermissions(new NameAndId(linked.uuid(), linked.name())),
				linked.name(), Component.literal(linked.name()), server, null);
	}

	/** Collects the feedback a command sends, so it can be shown in Discord. */
	private static class OutputCapture implements CommandSource {
		private final StringBuilder output = new StringBuilder();

		@Override
		public void sendSystemMessage(Component message) {
			output.append(message.getString()).append('\n');
		}

		@Override
		public boolean acceptsSuccess() {
			return true;
		}

		@Override
		public boolean acceptsFailure() {
			return true;
		}

		@Override
		public boolean shouldInformAdmins() {
			return true;
		}

		String toReply(String command) {
			String text = output.toString().strip().replace("```", "`​``");
			return "`/" + command.replace("`", "'") + "`\n" + (text.isEmpty() ? "*(no output)*" : "```\n" + text + "\n```");
		}
	}
}
