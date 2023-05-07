package dupetop.addon.utils;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

import static dupetop.addon.modules.PistonPush.prevSlot;
import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Utils {
    private long time = -1L;

    public Utils reset() {
        this.time = System.nanoTime();
        return this;
    }

    public boolean passedS(double s) {
        return this.passedMs((long) s * 1000L);
    }

    public boolean passedDms(double dms) {
        return this.passedMs((long) dms * 10L);
    }

    public boolean passedDs(double ds) {
        return this.passedMs((long) ds * 100L);
    }

    public boolean passedMs(long ms) {
        return this.passedNS(this.convertToNS(ms));
    }

    public void setMs(long ms) {
        this.time = System.nanoTime() - this.convertToNS(ms);
    }

    public boolean passedNS(long ns) {
        return System.nanoTime() - this.time >= ns;
    }

    public long getPassedTimeMs() {
        return this.getMs(System.nanoTime() - this.time);
    }

    public long getMs(long time) {
        return time / 1000000L;
    }

    public long convertToNS(long time) {
        return time * 1000000L;
    }
    public static Vec3d closestVec3d(BlockPos blockpos) {
        if (blockpos == null) return new Vec3d(0.0, 0.0, 0.0);
        double x = MathHelper.clamp((mc.player.getX() - blockpos.getX()), 0.0, 1.0);
        double y = MathHelper.clamp((mc.player.getY() - blockpos.getY()), 0.0, 0.6);
        double z = MathHelper.clamp((mc.player.getZ() - blockpos.getZ()), 0.0, 1.0);
        return new Vec3d(blockpos.getX() + x, blockpos.getY() + y, blockpos.getZ() + z);
    }
    public static BlockPos getBlockPos(PlayerEntity entity) {
        return entity.getBlockPos();
    }

    public static boolean isAir(BlockPos block) {
        return mc.world.getBlockState(block).isAir();
    }

    public static void placeBlock(Hand hand, BlockHitResult result, boolean packetPlace) {
        if (packetPlace) mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(hand, result,1));
        else mc.interactionManager.interactBlock(mc.player, hand, result);
    }
    public static void updateSlot(FindItemResult result, boolean packet) {
        updateSlot(result.slot(), packet);
    }
    public static void updateSlot(int slot, boolean packet) {
        if (slot < 0 || slot > 8) return;
        if (prevSlot == -1) prevSlot = mc.player.getInventory().selectedSlot;

        assert mc.player != null;
        mc.player.getInventory().selectedSlot = slot;
        if (packet) Objects.requireNonNull(mc.getNetworkHandler()).sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }
}