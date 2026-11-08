package locationlink;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one player sends the other to connect: their name, a public key for the encryption, and
 * the addresses their socket can be reached on (public from STUN, plus local network ones).
 *
 * <p>Written as {@code ll1.} followed by URL-safe base64, short enough for a /msg.
 *
 * @param reply true when this answers an invite, so the inviter's mod can connect without asking
 */
public record InviteCode(String name, byte[] publicKey, List<InetSocketAddress> endpoints, boolean reply) {
	public static final String PREFIX = "ll1.";
	private static final Pattern PATTERN = Pattern.compile("ll1\\.[A-Za-z0-9_-]{40,400}");
	private static final int VERSION = 1;
	private static final int MAX_ENDPOINTS = 6;

	public InviteCode {
		if (publicKey.length != 32) {
			throw new IllegalArgumentException("public key must be 32 bytes");
		}
		endpoints = List.copyOf(endpoints);
	}

	public InviteCode asReply(boolean isReply) {
		return new InviteCode(name, publicKey, endpoints, isReply);
	}

	public String encode() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(VERSION);
			out.writeByte(reply ? 1 : 0);
			out.write(publicKey);
			byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
			out.writeByte(nameBytes.length);
			out.write(nameBytes);
			List<InetSocketAddress> ipv4 = endpoints.stream().filter(e -> e.getAddress() instanceof Inet4Address).limit(MAX_ENDPOINTS).toList();
			out.writeByte(ipv4.size());
			for (InetSocketAddress endpoint : ipv4) {
				out.write(endpoint.getAddress().getAddress());
				out.writeShort(endpoint.getPort());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
	}

	/** Parses a code on its own, or throws IllegalArgumentException with a readable reason. */
	public static InviteCode decode(String text) {
		String code = text.trim();
		if (!code.startsWith(PREFIX)) {
			throw new IllegalArgumentException("That isn't a Location Link code.");
		}
		try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(
			Base64.getUrlDecoder().decode(code.substring(PREFIX.length()))))) {
			if (in.readUnsignedByte() != VERSION) {
				throw new IllegalArgumentException("That code is from a different version of Location Link.");
			}
			boolean reply = in.readUnsignedByte() == 1;
			byte[] key = new byte[32];
			in.readFully(key);
			byte[] nameBytes = new byte[in.readUnsignedByte()];
			in.readFully(nameBytes);
			int count = in.readUnsignedByte();
			if (count > MAX_ENDPOINTS) {
				throw new IllegalArgumentException("That code is damaged.");
			}
			List<InetSocketAddress> endpoints = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				byte[] ip = new byte[4];
				in.readFully(ip);
				endpoints.add(new InetSocketAddress(InetAddress.getByAddress(ip), in.readUnsignedShort()));
			}
			return new InviteCode(new String(nameBytes, StandardCharsets.UTF_8), key, endpoints, reply);
		} catch (IOException | IllegalStateException e) {
			throw new IllegalArgumentException("That code is damaged or incomplete.");
		} catch (IllegalArgumentException e) {
			if (e.getMessage() != null && e.getMessage().startsWith("That")) {
				throw e;
			}
			throw new IllegalArgumentException("That code is damaged or incomplete.");
		}
	}

	/** The first code inside a longer text such as a chat message, or null. */
	public static String find(String text) {
		Matcher matcher = PATTERN.matcher(text);
		return matcher.find() ? matcher.group() : null;
	}
}
