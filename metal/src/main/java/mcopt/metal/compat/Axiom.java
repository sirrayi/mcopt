package mcopt.metal.compat;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Axiom (com.moulberry.axiom) on the Metal backend. Axiom's mixin adds its RenderPipelineExt interface to the
 * renderpearl CreateInfo, marking pipelines that cull front faces; its GL and Vulkan backend mixins apply that, but
 * on Metal they aren't used, so those pipelines cull the wrong side. Read here by reflection, resolved once: no
 * compile-time dependency on Axiom.
 */
public final class Axiom {
	private static final String EXT = "com.moulberry.axiom.hooks.RenderPipelineExt";
	private static Class<?> ext;
	private static Method cullFront;
	private static boolean failed;

	private Axiom() {
	}

	/** The CreateInfo's axiom$isCullFrontFaces(), or false: Axiom absent, the info not extended, or reflection failed. */
	public static boolean cullFront(Object createInfo) {
		if (failed) return false;
		if (ext == null) {
			if (!FabricLoader.getInstance().isModLoaded("axiom")) return false;
			try {
				ext = Class.forName(EXT, true, Axiom.class.getClassLoader());
				cullFront = ext.getMethod("axiom$isCullFrontFaces");
			} catch (Throwable t) {
				failed = true;
				System.out.println("[mcopt] Axiom is loaded but RenderPipelineExt can't be read (" + t + "); front-face culling stays vanilla");
				return false;
			}
		}
		if (!ext.isInstance(createInfo)) return false;
		try {
			return (Boolean) cullFront.invoke(createInfo);
		} catch (Throwable t) {
			failed = true;
			System.out.println("[mcopt] Axiom axiom$isCullFrontFaces failed (" + t + "); front-face culling stays vanilla");
			return false;
		}
	}
}
