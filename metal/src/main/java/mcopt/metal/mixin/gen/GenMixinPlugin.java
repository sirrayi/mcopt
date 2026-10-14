package mcopt.metal.mixin.gen;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * The generation experiments' mixins apply only with their flags: -Dmcopt.gen.worldgenQos (GenWorldgenQosMixin),
 * -Dmcopt.gen.nativeNoise (GenNoiseChunkMixin).
 * Without them the game is byte for byte what it is without these mixins. Reads the properties itself, so nothing of
 * mcopt.metal.lod loads before the game does.
 */
public final class GenMixinPlugin implements IMixinConfigPlugin {
	static {
		mcopt.metal.Profile.apply(); // before any flag is read
	}

	private static final boolean WORLDGEN_QOS = System.getProperty("mcopt.gen.worldgenQos") != null;
	private static final boolean NATIVE_NOISE = !System.getProperty("mcopt.gen.nativeNoise", "").isBlank();

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".GenWorldgenQosMixin")) return WORLDGEN_QOS;
		if (mixinClassName.endsWith(".GenNoiseChunkMixin")) {
			// C2ME redirects the same RandomState.samplersWithContext in NoiseChunk.<init>; its noise wins either way.
			if (NATIVE_NOISE && net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("c2me")) {
				System.out.println("mcopt-gen: GenNoiseChunkMixin not applied: c2me is loaded");
				return false;
			}
			return NATIVE_NOISE;
		}
		return false;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
