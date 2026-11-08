package locationlink.test;

import java.nio.file.Files;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import locationlink.InviteCode;
import locationlink.LinkSession;
import locationlink.LocationLinkClient;
import locationlink.Position;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * The real client against a stand-in friend: a second {@link LinkSession} in this JVM, reached
 * over loopback. Codes arrive as real chat packets, so the hidden-message, [Accept] and
 * invite-reply paths all run as they would on a server.
 */
public class LocationLinkGameTest implements FabricClientGameTest {
	private static final List<String> NO_STUN = List.of();

	@Override
	public void runTest(ClientGameTestContext context) {
		try {
			// No STUN in the test: everything is on this machine.
			Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("locationlink.properties"), "port=0\nstun=\n");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			world.getServer().runCommand("tp @a 0 100 0");
			world.getServer().runCommand("gamemode creative @a");
			context.waitTicks(20);

			acceptAnInvite(context, world);
			inviteAndGetAReply(context);
		} catch (Exception e) {
			throw e instanceof RuntimeException r ? r : new RuntimeException(e);
		}
	}

	/** A friend's invite arrives in chat; clicking [Accept] links us. */
	private static void acceptAnInvite(ClientGameTestContext context, TestSingleplayerContext world) throws Exception {
		try (LinkSession friend = LinkSession.open("Friend", 0, NO_STUN, s -> log("friend: " + s))) {
			String code = friend.ownCode().encode();
			receiveChat(context, "Friend whispers to you: Location Link invite (needs the Location Link mod): " + code);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> lastChatLines(mc, 1).getFirst()).contains("Friend wants to share locations with you"),
				"no invite prompt: " + context.computeOnClient(mc -> lastChatLines(mc, 3)));
			check(context.computeOnClient(mc -> lastChatLines(mc, 4)).stream().noneMatch(line -> line.contains(code)),
				"the raw code wasn't hidden");
			log("invite prompt shown, raw code hidden");

			// The [Accept] button's command.
			context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand("link accept " + code, null));
			waitUntil(context, 10, () -> LocationLinkClient.session() != null && LocationLinkClient.session().peer() != null,
				() -> "accepting didn't connect");
			friend.connect(LocationLinkClient.session().ownCode());
			waitUntil(context, 10, () -> LocationLinkClient.session().state() == LinkSession.State.CONNECTED
				&& friend.state() == LinkSession.State.CONNECTED, () -> "not connected: " + LocationLinkClient.session().state());
			log("linked with the friend");

			// Our position reaches the friend.
			waitUntil(context, 5, () -> friend.peerPosition() != null, () -> "friend got no position");
			Position ours = friend.peerPosition();
			check(Math.abs(ours.x() - 0.5) < 1 && Math.abs(ours.z() - 0.5) < 1 && ours.dimension().equals("minecraft:overworld")
				&& ours.server().equals("singleplayer"), "friend got " + ours);
			log("friend sees us at " + ours);

			// The friend 300 blocks east: HUD line and Xaero waypoint.
			context.runOnClient(mc -> mc.player.setYRot(-90)); // facing east
			friend.sendPosition(new Position("Friend", "singleplayer", "minecraft:overworld", 300.5, 70, 0.5, 0, 17));
			waitUntil(context, 5, () -> LocationLinkClient.shownWaypoint() != null && LocationLinkClient.shownWaypoint()[0] == 300,
				() -> "no waypoint");
			List<String> hud = context.computeOnClient(LocationLinkClient::hudLines);
			check(hud.equals(List.of("Friend: 300 70 0 ♥17 · 300m ↑")), "HUD shows " + hud);
			log("HUD: " + hud + ", waypoint at " + java.util.Arrays.toString(LocationLinkClient.shownWaypoint()));
			context.takeScreenshot("locationlink-hud");

			// Behind us when facing west.
			context.runOnClient(mc -> mc.player.setYRot(90));
			context.waitTicks(2);
			check(context.computeOnClient(LocationLinkClient::hudLines).getFirst().endsWith("↓"), "arrow didn't turn");

			// The friend in the Nether: converted coordinates, no waypoint in our dimension.
			friend.sendPosition(new Position("Friend", "singleplayer", "minecraft:the_nether", 40, 80, -10, 0, 20));
			waitUntil(context, 5, () -> LocationLinkClient.shownWaypoint() == null, () -> "waypoint stayed after the friend left");
			hud = context.computeOnClient(LocationLinkClient::hudLines);
			check(hud.getFirst().equals("Friend (Nether): 40 80 -10 ♥20 ≈ 320 -80 here"), "HUD shows " + hud);
			log("Nether: " + hud);

			// The friend on another server.
			friend.sendPosition(new Position("Friend", "play.example.com", "minecraft:overworld", 1, 2, 3, 0, 20));
			waitUntil(context, 5, () -> context.computeOnClient(LocationLinkClient::hudLines).getFirst().contains("another server"),
				() -> "HUD shows " + context.computeOnClient(LocationLinkClient::hudLines));

			// The friend stops: our side closes too.
			friend.close();
			waitUntil(context, 10, () -> LocationLinkClient.session() == null, () -> "our side didn't notice the friend left");
			check(context.computeOnClient(LocationLinkClient::hudLines).isEmpty(), "HUD not cleared");
			log("friend left, link closed");
		}
	}

	/** We invite someone; their reply arrives in chat and links us without a click. */
	private static void inviteAndGetAReply(ClientGameTestContext context) throws Exception {
		context.runOnClient(mc -> mc.player.connection.sendCommand("link invite Friend"));
		waitUntil(context, 10, () -> LocationLinkClient.session() != null, () -> "invite didn't open a session");
		check(LocationLinkClient.session().peer() == null, "connected before any reply");

		try (LinkSession friend = LinkSession.open("Friend", 0, NO_STUN, s -> log("friend: " + s));
			LinkSession stranger = LinkSession.open("Stranger", 0, NO_STUN, s -> {})) {
			// A reply from someone we didn't invite is ignored.
			receiveChat(context, "Stranger whispers to you: Location Link reply: " + stranger.ownCode().asReply(true).encode());
			context.waitTicks(5);
			check(LocationLinkClient.session().peer() == null, "a stranger's reply connected us");

			friend.connect(LocationLinkClient.session().ownCode());
			receiveChat(context, "Friend whispers to you: Location Link reply: " + friend.ownCode().asReply(true).encode());
			waitUntil(context, 10, () -> LocationLinkClient.session().state() == LinkSession.State.CONNECTED,
				() -> "reply didn't connect: " + LocationLinkClient.session().state());
			log("invite and reply linked without a click");

			context.runOnClient(mc -> mc.player.connection.sendCommand("link stop"));
			waitUntil(context, 10, () -> friend.peerSaidBye(), () -> "friend wasn't told we stopped");
			check(LocationLinkClient.session() == null, "session left after /link stop");
			log("/link stop told the friend");
		}
	}

	private static void receiveChat(ClientGameTestContext context, String text) {
		context.runOnClient(mc -> mc.getConnection().handleSystemChat(new ClientboundSystemChatPacket(Component.literal(text), false)));
	}

	/** The newest received chat messages, newest first. */
	@SuppressWarnings("unchecked")
	private static List<String> lastChatLines(net.minecraft.client.Minecraft mc, int count) {
		try {
			var field = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages");
			field.setAccessible(true);
			var messages = (List<net.minecraft.client.multiplayer.chat.GuiMessage>) field.get(mc.gui.hud.getChat());
			List<String> lines = new java.util.ArrayList<>();
			for (int i = 0; i < messages.size() && lines.size() < count; i++) {
				lines.add(messages.get(i).content().getString());
			}
			return lines;
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static void waitUntil(ClientGameTestContext context, int seconds, BooleanSupplier done, Supplier<String> failure) {
		for (int tick = 0; tick < seconds * 20; tick++) {
			if (context.computeOnClient(mc -> done.getAsBoolean())) {
				return;
			}
			context.waitTick();
		}
		throw new AssertionError(failure.get());
	}

	private static void check(boolean condition, String failure) {
		if (!condition) {
			throw new AssertionError(failure);
		}
	}

	private static void log(String text) {
		System.out.println("[locationlink-gametest] " + text);
	}
}
