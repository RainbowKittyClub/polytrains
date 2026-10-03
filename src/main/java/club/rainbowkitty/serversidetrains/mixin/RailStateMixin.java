package club.rainbowkitty.serversidetrains.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.RailState;

import club.rainbowkitty.serversidetrains.block.JunctionRailBlock;
import club.rainbowkitty.serversidetrains.block.SwitchedRailBlock;

/** Keeps this mod's rails from being reshaped by the vanilla rail-connection pass. */
@Mixin(RailState.class)
public class RailStateMixin {

    // The rail block this state was read from.
    @Shadow @Final private BaseRailBlock block;

    // A junction rail names only two ends and a switched rail only the one leg it is thrown to, so
    // both read as not connecting on their other sides. Answering yes for all four lets neighbours
    // join them and keep that connection — a switch needs its idle leg joined for a passthrough.
    @Inject(method = "connectsTo", at = @At("HEAD"), cancellable = true)
    private void serversidetrains$connectOnEverySide(
            RailState rail, CallbackInfoReturnable<Boolean> cir) {
        if (this.block instanceof JunctionRailBlock || this.block instanceof SwitchedRailBlock) {
            cir.setReturnValue(true);
        }
    }

    // A switched rail's shape is set by its facing and redstone, never by a neighbouring rail
    // bending it. Vanilla's connectTo would otherwise curve it whenever a rail is laid alongside.
    @Inject(method = "connectTo", at = @At("HEAD"), cancellable = true)
    private void serversidetrains$dontReshapeSwitchedRail(RailState rail, CallbackInfo info) {
        if (this.block instanceof SwitchedRailBlock) {
            info.cancel();
        }
    }
}
