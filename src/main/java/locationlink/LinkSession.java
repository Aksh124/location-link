package locationlink;

import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One end of a direct, encrypted UDP link to another player.
 *
 * <ol>
 *   <li>{@link #open} binds a UDP socket and asks a STUN server for its public address.
 *   <li>The players swap {@link InviteCode}s, by /msg or by hand.
 *   <li>{@link #connect} works out the shared key and starts sending small "hello" packets to
 *       every address in the other player's code. Each side's packets open its own router's NAT
 *       for the other side's, so after a moment packets get through both ways ("hole punching").
 *   <li>Once a packet from the other player decrypts, that address is used from then on.
 * </ol>
 *
 * <p>Keys: X25519 key agreement, then AES-256-GCM on every packet with a counter as the nonce.
 * Packets that don't decrypt or are older than the last one are dropped.
 */
public final class LinkSession implements AutoCloseable {
	public enum State { WAITING_FOR_CODE, CONNECTING, CONNECTED, LOST, CLOSED }

	private static final byte MAGIC = 0x4C;
	private static final byte HELLO = 1;
	private static final byte POSITION = 2;
	private static final byte BYE = 3;
	private static final int HEADER = 10;
	/** Without a packet for this long, the link counts as lost (it keeps trying). */
	public static final long LOST_AFTER_MILLIS = 6_000;

	private final DatagramSocket socket;
	private final KeyPair keys;
	private final byte[] publicKey;
	private final InviteCode ownCode;
	private final String publicAddressNote;
	private final ScheduledExecutorService timer;
	private final Consumer<String> log;

	private volatile InviteCode peer;
	private volatile SecretKeySpec sharedKey;
	private volatile int ownDirection;
	private volatile InetSocketAddress lockedAddress;
	private volatile Position peerPosition;
	private volatile long lastReceivedMillis;
	private volatile boolean everConnected;
	private volatile boolean closed;
	private volatile boolean peerSaidBye;
	private long sendCounter;
	private long lastPeerCounter = -1;
	private final List<InetSocketAddress> candidates = Collections.synchronizedList(new ArrayList<>());

	private LinkSession(DatagramSocket socket, KeyPair keys, InviteCode ownCode, String publicAddressNote, Consumer<String> log) {
		this.socket = socket;
		this.keys = keys;
		this.publicKey = ownCode.publicKey();
		this.ownCode = ownCode;
		this.publicAddressNote = publicAddressNote;
		this.log = log;
		this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "Location Link timer");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Binds a socket and builds this player's code. Blocks for up to a few seconds while asking
	 * the STUN servers, so don't call it on the game thread.
	 *
	 * @param port 0 for any free port, or a fixed one if you've forwarded it on your router
	 * @param stunServers "host:port" entries, tried in order; empty skips STUN (local network only)
	 */
	public static LinkSession open(String name, int port, List<String> stunServers, Consumer<String> log) throws IOException {
		DatagramSocket socket = new DatagramSocket(new InetSocketAddress(port));
		try {
			KeyPair keys = KeyPairGenerator.getInstance("X25519").generateKeyPair();
			byte[] publicKey = rawKey((XECPublicKey) keys.getPublic());

			Set<InetSocketAddress> endpoints = new LinkedHashSet<>();
			String note = null;
			for (String server : stunServers) {
				try {
					InetSocketAddress mapped = Stun.publicAddress(socket, Stun.server(server), 1500, 2);
					endpoints.add(mapped);
					note = mapped.getAddress().getHostAddress() + ":" + mapped.getPort() + " (from " + server + ")";
					break;
				} catch (IOException e) {
					log.accept("STUN server " + server + " didn't answer: " + e.getMessage());
				}
			}
			endpoints.addAll(localEndpoints(socket.getLocalPort()));
			InviteCode code = new InviteCode(name, publicKey, new ArrayList<>(endpoints), false);
			return new LinkSession(socket, keys, code, note, log);
		} catch (GeneralSecurityException | RuntimeException e) {
			socket.close();
			throw new IOException(e);
		}
	}

	/** This computer's IPv4 addresses on its own networks, for players on the same LAN or PC. */
	static List<InetSocketAddress> localEndpoints(int port) {
		List<InetSocketAddress> local = new ArrayList<>();
		try {
			for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
					continue;
				}
				for (InetAddress address : Collections.list(network.getInetAddresses())) {
					if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
						local.add(new InetSocketAddress(address, port));
					}
				}
			}
		} catch (SocketException ignored) {
			// Only local network addresses are lost; the public one may still work.
		}
		local.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
		return local;
	}

	public InviteCode ownCode() {
		return ownCode;
	}

	/** Our public address as STUN saw it, or null if no STUN server answered. */
	public String publicAddressNote() {
		return publicAddressNote;
	}

	public InviteCode peer() {
		return peer;
	}

	public int localPort() {
		return socket.getLocalPort();
	}

	/** Starts connecting to the player whose code this is. */
	public synchronized void connect(InviteCode code) throws GeneralSecurityException {
		if (Arrays.equals(code.publicKey(), publicKey)) {
			throw new IllegalArgumentException("That's your own code.");
		}
		if (peer != null) {
			throw new IllegalStateException("Already linked with " + peer.name());
		}
		KeyAgreement agreement = KeyAgreement.getInstance("X25519");
		agreement.init(keys.getPrivate());
		agreement.doPhase(publicFromRaw(code.publicKey()), true);
		byte[] secret = agreement.generateSecret();

		boolean lower = Arrays.compareUnsigned(publicKey, code.publicKey()) < 0;
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		sha.update("location link v1".getBytes(StandardCharsets.US_ASCII));
		sha.update(secret);
		sha.update(lower ? publicKey : code.publicKey());
		sha.update(lower ? code.publicKey() : publicKey);
		sharedKey = new SecretKeySpec(sha.digest(), "AES");
		ownDirection = lower ? 0 : 1;
		candidates.addAll(code.endpoints());
		peer = code;

		Thread receiver = new Thread(this::receiveLoop, "Location Link receiver");
		receiver.setDaemon(true);
		receiver.start();
		timer.scheduleAtFixedRate(this::punch, 0, 400, TimeUnit.MILLISECONDS);
	}

	public State state() {
		if (closed) {
			return State.CLOSED;
		}
		if (peer == null) {
			return State.WAITING_FOR_CODE;
		}
		if (!everConnected) {
			return State.CONNECTING;
		}
		return System.currentTimeMillis() - lastReceivedMillis > LOST_AFTER_MILLIS || peerSaidBye ? State.LOST : State.CONNECTED;
	}

	public boolean peerSaidBye() {
		return peerSaidBye;
	}

	/** The other player's latest position, or null before the first one arrives. */
	public Position peerPosition() {
		return peerPosition;
	}

	public long millisSinceLastPacket() {
		return lastReceivedMillis == 0 ? -1 : System.currentTimeMillis() - lastReceivedMillis;
	}

	/** The address packets from the other player arrive from, once connected. */
	public InetSocketAddress peerAddress() {
		return lockedAddress;
	}

	/** Sends our position. Safe from any thread; does nothing until connected. */
	public void sendPosition(Position position) {
		if (sharedKey != null && !closed) {
			send(POSITION, position.encode());
		}
	}

	/**
	 * Until a packet gets through, keep knocking on every address the other player might have.
	 * After that, a hello whenever nothing else was sent for a second keeps both NATs open and
	 * tells the other side we're still here.
	 */
	private void punch() {
		if (closed || sharedKey == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!everConnected || now - lastReceivedMillis > 2_000 || now - lastSentMillis > 1_000) {
			send(HELLO, new byte[0]);
		}
	}

	private volatile long lastSentMillis;

	private synchronized void send(byte type, byte[] payload) {
		lastSentMillis = System.currentTimeMillis();
		try {
			long counter = sendCounter++;
			byte[] header = ByteBuffer.allocate(HEADER).put(MAGIC).put(type).putLong(counter).array();
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, sharedKey, new GCMParameterSpec(128, nonce(ownDirection, counter)));
			cipher.updateAAD(header);
			byte[] body = cipher.doFinal(payload);
			byte[] packet = ByteBuffer.allocate(HEADER + body.length).put(header).put(body).array();

			InetSocketAddress locked = lockedAddress;
			boolean fresh = locked != null && System.currentTimeMillis() - lastReceivedMillis < 2_000;
			List<InetSocketAddress> targets = fresh ? List.of(locked) : snapshotCandidates(locked);
			for (InetSocketAddress target : targets) {
				try {
					socket.send(new DatagramPacket(packet, packet.length, target));
				} catch (IOException e) {
					// One unreachable address (say, a LAN one from elsewhere) mustn't stop the rest.
				}
			}
		} catch (GeneralSecurityException e) {
			log.accept("Couldn't encrypt a packet: " + e);
		}
	}

	private List<InetSocketAddress> snapshotCandidates(InetSocketAddress locked) {
		synchronized (candidates) {
			List<InetSocketAddress> targets = new ArrayList<>(candidates);
			if (locked != null && !targets.contains(locked)) {
				targets.add(locked);
			}
			return targets;
		}
	}

	private void receiveLoop() {
		byte[] buffer = new byte[2048];
		while (!closed) {
			DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
			try {
				socket.receive(packet);
			} catch (IOException e) {
				if (!closed) {
					log.accept("Receive failed: " + e.getMessage());
				}
				continue;
			}
			handle(Arrays.copyOf(packet.getData(), packet.getLength()), (InetSocketAddress) packet.getSocketAddress());
		}
	}

	/** Package-private for tests. Returns true if the packet was accepted. */
	boolean handle(byte[] data, InetSocketAddress from) {
		if (data.length < HEADER + 16 || data[0] != MAGIC || sharedKey == null) {
			return false;
		}
		ByteBuffer header = ByteBuffer.wrap(data, 0, HEADER);
		header.get();
		byte type = header.get();
		long counter = header.getLong();
		byte[] plain;
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, sharedKey, new GCMParameterSpec(128, nonce(1 - ownDirection, counter)));
			cipher.updateAAD(data, 0, HEADER);
			plain = cipher.doFinal(data, HEADER, data.length - HEADER);
		} catch (GeneralSecurityException e) {
			return false; // Not from our peer, or tampered with.
		}
		synchronized (this) {
			if (counter <= lastPeerCounter) {
				return false; // Replayed or out of order.
			}
			lastPeerCounter = counter;
		}
		if (!from.equals(lockedAddress)) {
			log.accept((lockedAddress == null ? "Connected to " : "The other player's address changed to ")
				+ from.getAddress().getHostAddress() + ":" + from.getPort());
		}
		lockedAddress = from;
		lastReceivedMillis = System.currentTimeMillis();
		everConnected = true;
		peerSaidBye = type == BYE;
		if (type == POSITION) {
			try {
				peerPosition = Position.decode(plain);
			} catch (IOException e) {
				return false;
			}
		}
		return true;
	}

	private static byte[] nonce(int direction, long counter) {
		return ByteBuffer.allocate(12).putInt(direction).putLong(counter).array();
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		if (sharedKey != null) {
			send(BYE, new byte[0]);
		}
		closed = true;
		timer.shutdownNow();
		socket.close();
	}

	/** An X25519 public key as its 32-byte little-endian u-coordinate. */
	static byte[] rawKey(XECPublicKey key) {
		byte[] bigEndian = key.getU().toByteArray();
		byte[] little = new byte[32];
		for (int i = 0; i < 32 && i < bigEndian.length; i++) {
			little[i] = bigEndian[bigEndian.length - 1 - i];
		}
		return little;
	}

	static java.security.PublicKey publicFromRaw(byte[] little) throws GeneralSecurityException {
		byte[] bigEndian = new byte[33];
		for (int i = 0; i < 32; i++) {
			bigEndian[32 - i] = little[i];
		}
		return KeyFactory.getInstance("XDH").generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(bigEndian)));
	}
}
