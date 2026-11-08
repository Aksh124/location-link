package locationlink;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocationLinkClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("locationlink");
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Location Link setup");
		thread.setDaemon(true);
		return thread;
	});
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
	/** Give up connecting after this long; usually means both sides are behind strict NATs. */
	private static final long CONNECT_TIMEOUT_MILLIS = 45_000;

	private static volatile LinkSession session;
	private static boolean opening;
	/** Who we sent an invite to; their reply connects without asking. */
	private static String invited;
	private static LinkSession.State lastState;
	private static long connectStartedMillis;
	private static boolean hudEnabled = true;
	private static int ticks;

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> registerCommands(dispatcher));
		ClientTickEvents.END_CLIENT_TICK.register(LocationLinkClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> stop(false)));
		ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, time) ->
			onMessage(message, sender == null ? null : sender.name()));
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || onMessage(message, null));
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("locationlink", "partner"),
			(graphics, tickCounter) -> drawHud(graphics));
	}

	public static LinkSession session() {
		return session;
	}

	// Commands

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("link")
			.executes(context -> status(context.getSource()))
			.then(literal("invite").then(argument("player", StringArgumentType.word())
				.suggests((context, builder) -> {
					Minecraft mc = Minecraft.getInstance();
					if (mc.getConnection() != null) {
						for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
							String name = info.getProfile().name();
							if (!name.equals(mc.getUser().getName()) && name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
								builder.suggest(name);
							}
						}
					}
					return builder.buildFuture();
				})
				.executes(context -> invite(context.getSource(), StringArgumentType.getString(context, "player")))))
			.then(literal("accept").then(argument("code", StringArgumentType.greedyString())
				.executes(context -> accept(context.getSource(), StringArgumentType.getString(context, "code"), null))))
			.then(literal("code").executes(context -> showCode(context.getSource())))
			.then(literal("connect").then(argument("code", StringArgumentType.greedyString())
				.executes(context -> connectManually(context.getSource(), StringArgumentType.getString(context, "code")))))
			.then(literal("stop").executes(context -> {
				boolean had = session != null;
				stop(true);
				context.getSource().sendFeedback(Component.literal(had ? "Stopped sharing your location." : "You aren't linked with anyone."));
				return Command.SINGLE_SUCCESS;
			}))
			.then(literal("hud").executes(context -> {
				hudEnabled = !hudEnabled;
				context.getSource().sendFeedback(Component.literal("Location Link HUD " + (hudEnabled ? "on" : "off")));
				return Command.SINGLE_SUCCESS;
			})));
	}

	private static int status(FabricClientCommandSource source) {
		LinkSession current = session;
		if (current == null) {
			source.sendFeedback(Component.literal("Not linked. /link invite <player> to share locations with a friend, "
				+ "or /link code and /link connect <code> to swap codes yourselves."));
			return Command.SINGLE_SUCCESS;
		}
		InviteCode peer = current.peer();
		String text = switch (current.state()) {
			case WAITING_FOR_CODE -> invited != null ? "Waiting for " + invited + " to accept." : "Waiting for the other player's code.";
			case CONNECTING -> "Connecting to " + peer.name() + "...";
			case CONNECTED -> "Linked with " + peer.name() + " at " + describe(current.peerAddress()) + ".";
			case LOST -> "Lost the connection to " + peer.name() + ". Still trying.";
			case CLOSED -> "Closed.";
		};
		source.sendFeedback(Component.literal(text + (current.publicAddressNote() == null ? ""
			: " Your public address: " + current.publicAddressNote() + ". UDP port " + current.localPort() + ".")));
		return Command.SINGLE_SUCCESS;
	}

	private static int invite(FabricClientCommandSource source, String player) {
		Minecraft mc = Minecraft.getInstance();
		if (player.equalsIgnoreCase(mc.getUser().getName())) {
			source.sendError(Component.literal("You can't invite yourself."));
			return 0;
		}
		return withFreshSession(source, opened -> {
			invited = player;
			sendWhisper(player, "Location Link invite (needs the Location Link mod): " + opened.ownCode().encode());
			message(Component.literal("Invite sent to " + player + ". Your location is shared once they accept."));
			message(Component.literal("Your invite holds your IP address, and the server sees it like any /msg. Only invite people you trust.")
				.withStyle(ChatFormatting.GRAY));
		});
	}

	/** From the [Accept] button: connect, then answer with our own code. */
	private static int accept(FabricClientCommandSource source, String text, String replyTo) {
		InviteCode code;
		try {
			code = InviteCode.decode(text);
		} catch (IllegalArgumentException e) {
			source.sendError(Component.literal(e.getMessage()));
			return 0;
		}
		String target = replyTo != null ? replyTo : code.name();
		return withFreshSession(source, opened -> {
			if (connect(opened, code)) {
				sendWhisper(target, "Location Link reply: " + opened.ownCode().asReply(true).encode());
			}
		});
	}

	private static int showCode(FabricClientCommandSource source) {
		LinkSession current = session;
		if (current != null && current.peer() == null) {
			printOwnCode(current);
			return Command.SINGLE_SUCCESS;
		}
		return withFreshSession(source, LocationLinkClient::printOwnCode);
	}

	private static int connectManually(FabricClientCommandSource source, String text) {
		InviteCode code;
		try {
			code = InviteCode.decode(text);
		} catch (IllegalArgumentException e) {
			source.sendError(Component.literal(e.getMessage()));
			return 0;
		}
		LinkSession current = session;
		if (current != null && current.peer() == null) {
			connect(current, code);
			return Command.SINGLE_SUCCESS;
		}
		return withFreshSession(source, opened -> {
			if (connect(opened, code) && !code.reply()) {
				message(Component.literal("If " + code.name() + " doesn't have your code yet, send it to them: ")
					.append(copyable(opened.ownCode().asReply(true).encode())));
			}
		});
	}

	private static void printOwnCode(LinkSession current) {
		message(Component.literal("Your code (click to copy). Send it to your friend; they run /link connect <code>: ")
			.append(copyable(current.ownCode().encode())));
		message(Component.literal("It holds your IP address, so only share it with people you trust.").withStyle(ChatFormatting.GRAY));
	}

	/** Opens a new session off the game thread (STUN takes a moment), then runs the action on it. */
	private static int withFreshSession(FabricClientCommandSource source, Consumer<LinkSession> then) {
		LinkSession current = session;
		if (current != null && current.peer() != null) {
			source.sendError(Component.literal("You're already linked with " + current.peer().name() + ". Use /link stop first."));
			return 0;
		}
		if (opening) {
			source.sendError(Component.literal("Still setting up. Try again in a moment."));
			return 0;
		}
		Minecraft mc = Minecraft.getInstance();
		stop(false);
		opening = true;
		String name = mc.getUser().getName();
		Config config = Config.load();
		WORKER.execute(() -> {
			try {
				LinkSession opened = LinkSession.open(name, config.port(), config.stunServers(), LOGGER::info);
				mc.execute(() -> {
					opening = false;
					session = opened;
					lastState = opened.state();
					if (opened.publicAddressNote() == null) {
						message(Component.literal("Couldn't reach a STUN server, so this only works on your local network.")
							.withStyle(ChatFormatting.YELLOW));
					}
					then.accept(opened);
				});
			} catch (IOException e) {
				LOGGER.warn("Couldn't open the link", e);
				mc.execute(() -> {
					opening = false;
					message(Component.literal("Couldn't open a UDP socket: " + e.getMessage()).withStyle(ChatFormatting.RED));
				});
			}
		});
		source.sendFeedback(Component.literal("Setting up...").withStyle(ChatFormatting.GRAY));
		return Command.SINGLE_SUCCESS;
	}

	private static boolean connect(LinkSession current, InviteCode code) {
		try {
			current.connect(code);
			connectStartedMillis = System.currentTimeMillis();
			message(Component.literal("Connecting to " + code.name() + "..."));
			return true;
		} catch (Exception e) {
			message(Component.literal(e.getMessage() == null ? e.toString() : e.getMessage()).withStyle(ChatFormatting.RED));
			return false;
		}
	}

	/** Closes the link; the other player is told with a goodbye packet. */
	private static void stop(boolean byCommand) {
		LinkSession current = session;
		session = null;
		invited = null;
		lastState = null;
		if (current != null) {
			current.close();
		}
		if (Waypoints.available()) {
			XaeroBridge.hide();
		}
	}

	private static void sendWhisper(String player, String text) {
		LocalPlayer self = Minecraft.getInstance().player;
		if (self != null) {
			self.connection.sendCommand("msg " + player + " " + text);
		}
	}

	// Incoming invites and replies

	/** Returns false to hide a chat message that only carried a code. */
	private static boolean onMessage(Component message, String sender) {
		String text = InviteCode.find(message.getString());
		if (text == null) {
			return true;
		}
		InviteCode code;
		try {
			code = InviteCode.decode(text);
		} catch (IllegalArgumentException e) {
			return true;
		}
		LinkSession current = session;
		if (current != null && Arrays.equals(code.publicKey(), current.ownCode().publicKey())) {
			return false; // Our own whisper echoed back.
		}
		String from = sender != null ? sender : code.name();
		Minecraft.getInstance().execute(() -> handleCode(code, text, from));
		return false;
	}

	private static void handleCode(InviteCode code, String text, String from) {
		LinkSession current = session;
		if (code.reply() && current != null && current.peer() == null && invited != null
			&& (invited.equalsIgnoreCase(from) || invited.equalsIgnoreCase(code.name()))) {
			message(Component.literal(code.name() + " accepted."));
			connect(current, code);
			return;
		}
		if (code.reply()) {
			return; // An answer to an invite we didn't send, or one we've cancelled.
		}
		MutableComponent prompt = Component.literal(from + " wants to share locations with you. ");
		prompt.append(Component.literal("[Accept]").withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand("/link accept " + text))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Connect directly to " + from + " and share your location")))));
		message(prompt);
	}

	// Every tick: send our position, notice connection changes, update the waypoint.

	private static void tick(Minecraft mc) {
		LinkSession current = session;
		if (current == null || mc.player == null || mc.level == null) {
			return;
		}
		ticks++;
		LinkSession.State state = current.state();
		if (state != lastState) {
			onStateChange(current, lastState, state);
			lastState = state;
		}
		if (current != session) {
			return; // onStateChange stopped it.
		}
		if (state == LinkSession.State.CONNECTING && System.currentTimeMillis() - connectStartedMillis > CONNECT_TIMEOUT_MILLIS) {
			message(Component.literal("Couldn't get through to " + current.peer().name() + ". Both of you may be behind strict NATs "
				+ "(common on mobile data and some ISPs). Forwarding a UDP port on one router and setting it in "
				+ "config/locationlink.properties usually fixes it.").withStyle(ChatFormatting.RED));
			stop(true);
			return;
		}
		if (current.peer() != null && ticks % 5 == 0) {
			LocalPlayer player = mc.player;
			current.sendPosition(new Position(mc.getUser().getName(), serverKey(mc), mc.level.dimension().identifier().toString(),
				player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getHealth()));
		}
		if (Waypoints.available() && ticks % 10 == 0) {
			Position peer = current.peerPosition();
			if (peer != null && state != LinkSession.State.CLOSED && sameWorld(mc, peer)) {
				XaeroBridge.show(peer.name(), Mth.floor(peer.x()), Mth.floor(peer.y()), Mth.floor(peer.z()));
			} else {
				XaeroBridge.hide();
			}
		}
	}

	private static void onStateChange(LinkSession current, LinkSession.State from, LinkSession.State to) {
		String name = current.peer() == null ? "" : current.peer().name();
		switch (to) {
			case CONNECTED -> message(Component.literal(from == LinkSession.State.LOST ? "Reconnected to " + name + "."
				: "Linked with " + name + ". You can now see each other's location.").withStyle(ChatFormatting.GREEN));
			case LOST -> {
				if (current.peerSaidBye()) {
					message(Component.literal(name + " stopped sharing their location."));
					stop(false);
				} else {
					message(Component.literal("Lost the connection to " + name + ". Still trying...").withStyle(ChatFormatting.YELLOW));
				}
			}
			default -> {}
		}
	}

	private static boolean sameWorld(Minecraft mc, Position peer) {
		return peer.server().equals(serverKey(mc)) && peer.dimension().equals(mc.level.dimension().identifier().toString());
	}

	static String serverKey(Minecraft mc) {
		ServerData server = mc.getCurrentServer();
		return server != null ? server.ip.toLowerCase(Locale.ROOT) : "singleplayer";
	}

	// HUD

	/** Where the other player's Xaero waypoint is, or null. For the game test. */
	public static int[] shownWaypoint() {
		return Waypoints.available() ? XaeroBridge.shown() : null;
	}

	/** The HUD lines, top centre. Public for the game test. */
	public static List<String> hudLines(Minecraft mc) {
		List<String> lines = new ArrayList<>();
		LinkSession current = session;
		if (current == null || mc.player == null || mc.level == null) {
			return lines;
		}
		InviteCode peer = current.peer();
		switch (current.state()) {
			case WAITING_FOR_CODE -> lines.add(invited != null ? "Location Link: waiting for " + invited : "Location Link: waiting for a code");
			case CONNECTING -> lines.add("Location Link: connecting to " + peer.name() + "...");
			case CLOSED -> {}
			case CONNECTED, LOST -> {
				Position position = current.peerPosition();
				if (position == null) {
					lines.add(peer.name() + ": waiting for their position");
				} else {
					lines.add(describe(mc, position));
				}
				if (current.state() == LinkSession.State.LOST) {
					lines.add("connection lost " + current.millisSinceLastPacket() / 1000 + "s ago, retrying");
				}
			}
		}
		return lines;
	}

	private static String describe(Minecraft mc, Position peer) {
		String coords = Mth.floor(peer.x()) + " " + Mth.floor(peer.y()) + " " + Mth.floor(peer.z());
		String health = String.format(Locale.ROOT, " ♥%.0f", peer.health());
		if (!peer.server().equals(serverKey(mc))) {
			return peer.name() + " is on another server (" + peer.server() + ")";
		}
		String ours = mc.level.dimension().identifier().toString();
		if (!peer.dimension().equals(ours)) {
			String text = peer.name() + " (" + dimensionName(peer.dimension()) + "): " + coords + health;
			double scale = scale(peer.dimension()) / scale(ours);
			if (!Double.isNaN(scale)) {
				text += " ≈ " + Mth.floor(peer.x() * scale) + " " + Mth.floor(peer.z() * scale) + " here";
			}
			return text;
		}
		LocalPlayer self = mc.player;
		double dx = peer.x() - self.getX();
		double dz = peer.z() - self.getZ();
		int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
		float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
		int arrow = Math.floorMod(Math.round(Mth.wrapDegrees(bearing - self.getYRot()) / 45f), 8);
		return peer.name() + ": " + coords + health + " · " + distance + "m " + ARROWS[arrow];
	}

	/** Overworld blocks per block in this dimension, or NaN where it doesn't map (the End). */
	private static double scale(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> 1;
			case "minecraft:the_nether" -> 8;
			default -> Double.NaN;
		};
	}

	private static String dimensionName(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> dimension;
		};
	}

	private static void drawHud(GuiGraphicsExtractor graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (!hudEnabled || mc.gui.hud.isHidden()) {
			return;
		}
		int lineHeight = mc.font.lineHeight + 1;
		int y = 4;
		for (String line : hudLines(mc)) {
			int width = mc.font.width(line);
			int left = (graphics.guiWidth() - width) / 2;
			graphics.fill(left - 2, y - 1, left + width + 2, y + lineHeight - 1, 0x80000000);
			graphics.text(mc.font, line, left, y, 0xFF55FFFF);
			y += lineHeight;
		}
	}

	// Chat helpers

	private static String describe(java.net.InetSocketAddress address) {
		return address == null ? "?" : address.getAddress().getHostAddress() + ":" + address.getPort();
	}

	private static MutableComponent copyable(String text) {
		return Component.literal(text).withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withUnderlined(true)
			.withClickEvent(new ClickEvent.CopyToClipboard(text))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))));
	}

	static void message(Component text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.empty()
				.append(Component.literal("[Location Link] ").withStyle(ChatFormatting.AQUA))
				.append(text));
		}
		LOGGER.info(text.getString());
	}

	// Settings in config/locationlink.properties

	record Config(int port, List<String> stunServers) {
		private static final String DEFAULT_STUN = "stun.l.google.com:19302,stun.cloudflare.com:3478";

		static Config load() {
			Path file = FabricLoader.getInstance().getConfigDir().resolve("locationlink.properties");
			Properties properties = new Properties();
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					properties.load(reader);
				} catch (IOException e) {
					LOGGER.warn("Couldn't read {}", file, e);
				}
			} else {
				properties.setProperty("port", "0");
				properties.setProperty("stun", DEFAULT_STUN);
				try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
					properties.store(writer, "Location Link. port: UDP port to use, 0 for any free one (set a fixed one if you "
						+ "forward it on your router). stun: servers that tell you your public address, comma separated; "
						+ "leave empty to only connect on your local network.");
				} catch (IOException e) {
					LOGGER.warn("Couldn't write {}", file, e);
				}
			}
			int port;
			try {
				port = Integer.parseInt(properties.getProperty("port", "0").trim());
			} catch (NumberFormatException e) {
				port = 0;
			}
			List<String> stun = new ArrayList<>();
			for (String server : properties.getProperty("stun", DEFAULT_STUN).split(",")) {
				if (!server.isBlank()) {
					stun.add(server.trim());
				}
			}
			return new Config(port, stun);
		}
	}
}
