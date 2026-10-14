package mcopt.bench.mixin;

import mcopt.bench.Bench;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drives the bench once per frame. Without -Dmcopt.bench the call returns at once. */
@Mixin(Minecraft.class)
abstract class MinecraftBenchMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void mcoptBench$frame(boolean advanceGameTime, CallbackInfo ci) {
		Bench.frame();
	}
}
