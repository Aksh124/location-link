package locationlink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One player's location as sent over the link.
 *
 * @param server which server they're on ("play.example.com", or "singleplayer"), so a friend on
 *               a different server isn't mistaken for one nearby
 * @param dimension a dimension id such as "minecraft:the_nether"
 */
public record Position(String name, String server, String dimension, double x, double y, double z, float yaw, float health) {
	public byte[] encode() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeUTF(name);
			out.writeUTF(server);
			out.writeUTF(dimension);
			out.writeDouble(x);
			out.writeDouble(y);
			out.writeDouble(z);
			out.writeFloat(yaw);
			out.writeFloat(health);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	public static Position decode(byte[] data) throws IOException {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			return new Position(in.readUTF(), in.readUTF(), in.readUTF(), in.readDouble(), in.readDouble(), in.readDouble(),
				in.readFloat(), in.readFloat());
		}
	}
}
