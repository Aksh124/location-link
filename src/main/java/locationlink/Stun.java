package locationlink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * The smallest useful part of STUN (RFC 5389): ask a public server which address and port our
 * UDP socket appears to come from. That's the address the other player has to send to.
 */
public final class Stun {
	private static final int MAGIC_COOKIE = 0x2112A442;
	private static final short BINDING_REQUEST = 0x0001;
	private static final short BINDING_SUCCESS = 0x0101;
	private static final short MAPPED_ADDRESS = 0x0001;
	private static final short XOR_MAPPED_ADDRESS = 0x0020;
	private static final SecureRandom RANDOM = new SecureRandom();

	private Stun() {}

	/** Resolves "host:port" to an IPv4 address. */
	public static InetSocketAddress server(String hostAndPort) throws IOException {
		int colon = hostAndPort.lastIndexOf(':');
		String host = colon < 0 ? hostAndPort : hostAndPort.substring(0, colon);
		int port = colon < 0 ? 3478 : Integer.parseInt(hostAndPort.substring(colon + 1).trim());
		for (InetAddress address : InetAddress.getAllByName(host.trim())) {
			if (address instanceof Inet4Address) {
				return new InetSocketAddress(address, port);
			}
		}
		throw new IOException("No IPv4 address for " + host);
	}

	/**
	 * Sends a binding request from this socket and returns our public address, or throws if the
	 * server doesn't answer. Call before anything else reads from the socket.
	 */
	public static InetSocketAddress publicAddress(DatagramSocket socket, InetSocketAddress server, int timeoutMillis, int attempts)
		throws IOException {
		byte[] transaction = new byte[12];
		RANDOM.nextBytes(transaction);
		ByteBuffer request = ByteBuffer.allocate(20);
		request.putShort(BINDING_REQUEST).putShort((short) 0).putInt(MAGIC_COOKIE).put(transaction);

		int oldTimeout = socket.getSoTimeout();
		socket.setSoTimeout(timeoutMillis);
		try {
			byte[] buffer = new byte[512];
			for (int attempt = 0; attempt < attempts; attempt++) {
				socket.send(new DatagramPacket(request.array(), 20, server));
				long deadline = System.currentTimeMillis() + timeoutMillis;
				while (System.currentTimeMillis() < deadline) {
					DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
					try {
						socket.receive(packet);
					} catch (SocketTimeoutException e) {
						break;
					}
					InetSocketAddress mapped = parseResponse(Arrays.copyOf(packet.getData(), packet.getLength()), transaction);
					if (mapped != null) {
						return mapped;
					}
				}
			}
		} finally {
			socket.setSoTimeout(oldTimeout);
		}
		throw new IOException("No answer from STUN server " + server);
	}

	/** The mapped address in a binding success response for this transaction, or null. */
	static InetSocketAddress parseResponse(byte[] data, byte[] transaction) throws IOException {
		if (data.length < 20) {
			return null;
		}
		ByteBuffer in = ByteBuffer.wrap(data);
		short type = in.getShort();
		int length = in.getShort() & 0xFFFF;
		int cookie = in.getInt();
		byte[] id = new byte[12];
		in.get(id);
		if (type != BINDING_SUCCESS || cookie != MAGIC_COOKIE || !Arrays.equals(id, transaction) || 20 + length > data.length) {
			return null;
		}
		InetSocketAddress plain = null;
		while (in.position() + 4 <= 20 + length) {
			short attribute = in.getShort();
			int size = in.getShort() & 0xFFFF;
			int next = in.position() + ((size + 3) & ~3);
			if (next > data.length) {
				break;
			}
			if ((attribute == XOR_MAPPED_ADDRESS || attribute == MAPPED_ADDRESS) && size >= 8 && data[in.position() + 1] == 0x01) {
				in.get(); // reserved
				in.get(); // family: IPv4
				int port = in.getShort() & 0xFFFF;
				byte[] ip = new byte[4];
				in.get(ip);
				if (attribute == XOR_MAPPED_ADDRESS) {
					port ^= MAGIC_COOKIE >>> 16;
					for (int i = 0; i < 4; i++) {
						ip[i] ^= (byte) (MAGIC_COOKIE >>> (24 - 8 * i));
					}
					return new InetSocketAddress(InetAddress.getByAddress(ip), port);
				}
				plain = new InetSocketAddress(InetAddress.getByAddress(ip), port);
			}
			in.position(next);
		}
		return plain;
	}
}
