package locationlink;

import net.fabricmc.loader.api.FabricLoader;

/** Whether Xaero's Minimap is there. Checked before anything touches {@link XaeroBridge}. */
final class Waypoints {
	private Waypoints() {}

	static boolean available() {
		return FabricLoader.getInstance().isModLoaded("xaerominimap");
	}
}
