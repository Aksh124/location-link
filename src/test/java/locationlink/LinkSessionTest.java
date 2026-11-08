package locationlink;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class LinkSessionTest {
	private static final List<String> NO_STUN = List.of();

	@Test
	void inviteCodeRoundTripsAndIsShort() throws Exception {
		byte[] key = new byte[32];
		for (int i = 0; i < 32; i++) {
			key[i] = (byte) (i * 7);
		}
		InviteCode code = new InviteCode("Akshay_123", key, List.of(
			new InetSocketAddress(InetAddress.getByName("203.0.113.9"), 51234),
			new InetSocketAddress(InetAddress.getByName("192.168.1.20"), 51234)), true);
		String text = code.encode();
		System.out.println("code: " + text + " (" + text.length() + " chars)");
		assertTrue(text.length() < 120, "too long for chat: " + text.length());

		InviteCode back = InviteCode.decode(text);
		assertEquals("Akshay_123", back.name());
		assertArrayEquals(key, back.publicKey());
		assertEquals(code.endpoints(), back.endpoints());
		assertTrue(back.reply());

		assertEquals(text, InviteCode.find("Steve whispers to you: Location Link invite " + text + " (needs the mod)"));
		assertNull(InviteCode.find("no code here ll1.short"));
		assertThrows(IllegalArgumentException.class, () -> InviteCode.decode("ll1.AAAA"));
		assertThrows(IllegalArgumentException.class, () -> InviteCode.decode("hello"));
	}

	@Test
	void parsesStunXorMappedAddress() throws Exception {
		// RFC 5769 section 2.2: an IPv4 response mapping to 192.0.2.1:32853.
		byte[] transaction = HexFormat.of().parseHex("b7e7a701bc34d686fa87dfae");
		ByteBuffer response = ByteBuffer.allocate(32);
		response.putShort((short) 0x0101).putShort((short) 12).putInt(0x2112A442).put(transaction);
		response.putShort((short) 0x0020).putShort((short) 8);
		response.put(HexFormat.of().parseHex("0001a147e112a643"));
		InetSocketAddress mapped = Stun.parseResponse(response.array(), transaction);
		assertEquals(new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853), mapped);

		byte[] otherTransaction = transaction.clone();
		otherTransaction[0]++;
		assertNull(Stun.parseResponse(response.array(), otherTransaction));
	}

	@Test
	void rawKeysRoundTrip() throws Exception {
		for (int i = 0; i < 50; i++) {
			var pair = java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair();
			byte[] raw = LinkSession.rawKey((java.security.interfaces.XECPublicKey) pair.getPublic());
			var back = (java.security.interfaces.XECPublicKey) LinkSession.publicFromRaw(raw);
			assertEquals(((java.security.interfaces.XECPublicKey) pair.getPublic()).getU(), back.getU());
		}
	}

	@Test
	void twoPlayersShareLocations() throws Exception {
		List<String> log = new ArrayList<>();
		try (LinkSession alice = LinkSession.open("Alice", 0, NO_STUN, log::add);
			LinkSession bob = LinkSession.open("Bob", 0, NO_STUN, log::add)) {
			assertEquals(LinkSession.State.WAITING_FOR_CODE, alice.state());

			// Codes go through text, as they would in chat.
			InviteCode aliceCode = InviteCode.decode(alice.ownCode().encode());
			InviteCode bobCode = InviteCode.decode(bob.ownCode().asReply(true).encode());
			alice.connect(bobCode);
			assertEquals(LinkSession.State.CONNECTING, alice.state());
			Thread.sleep(700); // Bob connects later, like a player clicking Accept.
			bob.connect(aliceCode);

			waitFor(() -> alice.state() == LinkSession.State.CONNECTED && bob.state() == LinkSession.State.CONNECTED, "connect");

			Position here = new Position("Alice", "play.example.com", "minecraft:overworld", 123.5, 64, -987.25, 90, 18);
			alice.sendPosition(here);
			waitFor(() -> here.equals(bob.peerPosition()), "Alice's position to reach Bob");
			Position there = new Position("Bob", "play.example.com", "minecraft:the_nether", -5, 70, 12, -45, 20);
			bob.sendPosition(there);
			waitFor(() -> there.equals(alice.peerPosition()), "Bob's position to reach Alice");
			assertThrows(IllegalStateException.class, () -> alice.connect(bobCode));
			assertThrows(IllegalArgumentException.class, () -> bob.connect(bob.ownCode()));

			// Bob says goodbye: Alice sees the link as lost right away.
			bob.close();
			waitFor(() -> alice.state() == LinkSession.State.LOST && alice.peerSaidBye(), "Alice to notice Bob left");
		}
		System.out.println(log);
	}

	@Test
	void rejectsTamperedReplayedAndForeignPackets() throws Exception {
		try (LinkSession alice = LinkSession.open("Alice", 0, NO_STUN, s -> {});
			LinkSession bob = LinkSession.open("Bob", 0, NO_STUN, s -> {});
			LinkSession mallory = LinkSession.open("Mallory", 0, NO_STUN, s -> {});
			DatagramSocket sniffer = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
			// Bob's packets for Alice are sent to the sniffer, so the test can replay and alter them.
			InetSocketAddress snifferAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), sniffer.getLocalPort());
			alice.connect(bob.ownCode());
			bob.connect(new InviteCode("Alice", alice.ownCode().publicKey(), List.of(snifferAddress), false));
			bob.sendPosition(new Position("Bob", "s", "minecraft:overworld", 1, 2, 3, 0, 20));

			sniffer.setSoTimeout(3000);
			byte[] captured = null;
			for (int i = 0; i < 10 && captured == null; i++) {
				DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
				sniffer.receive(packet);
				byte[] data = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
				if (data[1] == 2) {
					captured = data;
				}
			}
			assertNotNull(captured, "didn't capture a position packet");
			InetSocketAddress from = new InetSocketAddress(InetAddress.getLoopbackAddress(), bob.localPort());

			byte[] tampered = captured.clone();
			tampered[tampered.length - 20] ^= 1;
			assertFalse(alice.handle(tampered, from), "accepted a tampered packet");
			assertTrue(alice.handle(captured, from), "rejected Bob's real packet");
			assertEquals(new Position("Bob", "s", "minecraft:overworld", 1, 2, 3, 0, 20), alice.peerPosition());
			assertFalse(alice.handle(captured, from), "accepted a replayed packet");

			// Mallory has Alice's public code but not Bob's private key, so her packets use another key.
			// Her hellos push her counter past Bob's, so only the decryption can reject her position.
			mallory.connect(new InviteCode("Alice", alice.ownCode().publicKey(), List.of(snifferAddress), false));
			Thread.sleep(1500);
			Position fake = new Position("Bob", "s", "minecraft:overworld", 999, 999, 999, 0, 20);
			mallory.sendPosition(fake);
			byte[] forged = null;
			for (int i = 0; i < 30 && forged == null; i++) {
				DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
				sniffer.receive(packet);
				byte[] data = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
				if (data[1] == 2 && packet.getPort() == mallory.localPort()) {
					forged = data;
				}
			}
			assertNotNull(forged, "didn't capture Mallory's packet");
			assertTrue(ByteBuffer.wrap(forged, 2, 8).getLong() > ByteBuffer.wrap(captured, 2, 8).getLong());
			assertFalse(alice.handle(forged, from), "accepted a packet from someone else");
			assertEquals(1, alice.peerPosition().x(), "Mallory changed Bob's position");
		}
	}

	@Test
	void opensWithLocalEndpoints() throws Exception {
		try (LinkSession session = LinkSession.open("Alice", 0, NO_STUN, s -> {})) {
			assertTrue(session.ownCode().endpoints().stream()
				.anyMatch(e -> e.getAddress().isLoopbackAddress() && e.getPort() == session.localPort()));
			assertNull(session.publicAddressNote());
		}
	}

	/** Asks real STUN servers. Opt in with -Dlocationlink.liveStun=true. */
	@Test
	void liveStun() throws Exception {
		if (!Boolean.getBoolean("locationlink.liveStun")) {
			return;
		}
		try (LinkSession session = LinkSession.open("Alice", 0, List.of("stun.l.google.com:19302", "stun.cloudflare.com:3478"),
			System.out::println)) {
			assertNotNull(session.publicAddressNote(), "no STUN server answered");
			System.out.println("public address: " + session.publicAddressNote());
		}
	}

	private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 8000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("timed out waiting for " + what);
			}
			Thread.sleep(20);
		}
	}
}
