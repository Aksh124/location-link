package locationlink;

import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

/**
 * The other player as a live waypoint in Xaero's Minimap. Only loaded when that mod is installed,
 * and only called on the client thread.
 *
 * <p>The waypoint is marked temporary, so Xaero never saves it to your waypoint files.
 */
final class XaeroBridge {
	private static Waypoint waypoint;
	private static WaypointSet set;

	private XaeroBridge() {}

	static void show(String name, int x, int y, int z) {
		MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
		MinimapWorld world = session == null ? null : session.getWorldManager().getCurrentWorld();
		if (world == null) {
			return;
		}
		WaypointSet current = world.getCurrentWaypointSet();
		if (waypoint == null || set != current || !name.equals(waypoint.getName())) {
			hide();
			waypoint = new Waypoint(x, y, z, name, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT),
				WaypointColor.AQUA);
			waypoint.setTemporary(true);
			current.add(waypoint);
			set = current;
		} else {
			waypoint.setX(x);
			waypoint.setY(y);
			waypoint.setZ(z);
		}
	}

	static void hide() {
		if (waypoint != null && set != null) {
			set.remove(waypoint);
		}
		waypoint = null;
		set = null;
	}

	/** For the game test: where the waypoint is, or null. */
	static int[] shown() {
		return waypoint == null ? null : new int[] {waypoint.getX(), waypoint.getY(), waypoint.getZ()};
	}
}
