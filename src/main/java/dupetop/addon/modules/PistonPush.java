package dupetop.addon.modules;

import dupetop.addon.Main;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import oshi.jna.platform.mac.SystemB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

import static dupetop.addon.utils.Utils.*;
import static java.util.Objects.isNull;
import static meteordevelopment.meteorclient.utils.entity.TargetUtils.getPlayerTarget;
import static meteordevelopment.meteorclient.utils.entity.TargetUtils.isBadTarget;
import static meteordevelopment.meteorclient.utils.player.InvUtils.findInHotbar;
import static meteordevelopment.meteorclient.utils.player.PlayerUtils.distanceTo;
import static meteordevelopment.meteorclient.utils.world.BlockUtils.canPlace;

public class PistonPush extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPause = settings.createGroup("Pause");

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder().name("target-range").description("Range in which to target players.").defaultValue(6).sliderRange(0, 10).build());
    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder().name("place-range").description("Range in which to place blocks.").defaultValue(4.6).sliderRange(0, 7).build());
    private final Setting<Prioity> prio = sgGeneral.add(new EnumSetting.Builder<Prioity>()
            .name("priority-distance")
            .description("Which target to prioritize")
            .defaultValue(Prioity.Closest)
            .build()
    );
    private final Setting<Boolean> packetPlace = sgGeneral.add(new BoolSetting.Builder().name("packet").description("Using packet interaction instead of client.").defaultValue(false).build());

    private final Setting<Order> order = sgGeneral.add(new EnumSetting.Builder<Order>()
            .name("place-order")
            .description("The order to place blocks.")
            .defaultValue(Order.Piston)
            .build()
    );

    //  soon tm  private final Setting<Boolean> holeFill = sgGeneral.add(new BoolSetting.Builder().name("hole-fill").description("Places obsidian inside of the target block.").defaultValue(true).build());
    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder().name("swap-back").description("Automatically swaps to previous slot.").defaultValue(true).build());
    private final Setting<Boolean> zeroTick = sgGeneral.add(new BoolSetting.Builder().name("zero-tick").description("Places all blocks in one tick.").defaultValue(true).build());

    // PAUSE
    private final Setting<Boolean> pause = sgPause.add(new BoolSetting.Builder().name("pause").description("").defaultValue(true).build());

    private final Setting<Boolean> eatPause = sgPause.add(new BoolSetting.Builder().name("pause-on-eat").description("Pauses if player is eating.").defaultValue(true).visible(pause::get).build());

    private final Setting<Boolean> drinkPause = sgPause.add(new BoolSetting.Builder().name("pause-on-drink").description("Pauses if drinking.").defaultValue(false).visible(pause::get).build());
    private final Setting<Boolean> placePause = sgPause.add(new BoolSetting.Builder().name("place-on-pause").description("Pauses if placing.").defaultValue(false).visible(pause::get).build());

    public PistonPush() {
        super(Main.CATEGORY, "piston-push", "Pushes players out of holes");
    }

    private BlockPos pistonPos;
    private BlockPos activatorPos;
    private Direction direction;

    private PlayerEntity target;
    private Stage stage;
    public static int prevSlot;
    public enum Order {
        Redstone, Piston
    }
    public enum Prioity {
        Closest,Furthest,HighestHealth,LowestHealth
    }

    @Override
    public void onActivate() {
        pistonPos = null;
        activatorPos = null;
        direction = null;

        stage = Stage.Preparing;
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        target = getPlayerTarget(targetRange.get(), sort()); // OLD BT DEVS DIDNT GET THISI RIGHT :sKUL:
        if (isBadTarget(target, targetRange.get())) {
            info("No nearby targets");
            toggle();
            return;
        }

        if (!findInHotbar(Items.PISTON, Items.STICKY_PISTON).found() || !findInHotbar(Items.REDSTONE_BLOCK).found()) {
            info("Items not found");
            toggle();
            return;
        }

        if (pause.get() && PlayerUtils.shouldPause(placePause.get(), eatPause.get(), drinkPause.get())) return;

        switch (stage) {
            case Preparing -> {
                assert mc.player != null;
                prevSlot = mc.player.getInventory().selectedSlot;

                BlockPos targetPos = getBlockPos(target).up();
                pistonPos = getPistonPos(targetPos);
                activatorPos = getRedstonePos(pistonPos);

                if (hasNull(targetPos, pistonPos, activatorPos)) stage = Stage.Toggle;
                if (hasFar(targetPos, pistonPos, activatorPos)) stage = Stage.Toggle;


                stage = zeroTick.get() ? Stage.ZeroTick : (placeOrder() ? Stage.Redstone : Stage.Piston);
            }
            case Piston -> {
                doPlace(findInHotbar(Items.PISTON, Items.STICKY_PISTON), pistonPos);
                stage = Stage.Redstone;
            }
            case Redstone -> {
                doPlace(findInHotbar(Items.REDSTONE_BLOCK), activatorPos);
                stage = Stage.Piston;
            }
            case ZeroTick -> {
                doPlace(findInHotbar(Items.PISTON, Items.STICKY_PISTON), pistonPos);
                doPlace(findInHotbar(Items.REDSTONE_BLOCK), activatorPos);
                stage = Stage.Toggle;
            }
            case Toggle -> {
                if (swapBack.get()) {
                    assert mc.player != null;
                    mc.player.getInventory().selectedSlot = prevSlot;
                }
                toggle();
            }
        }
    }

    private void doPlace(FindItemResult result, BlockPos blockPos) {
        if (isNull(blockPos)) return;
        Hand hand = result.isOffhand() ? Hand.OFF_HAND : Hand.MAIN_HAND;

        Rotations.rotate(getYaw(direction), 0, () -> {
            updateSlot(result, true);
            placeBlock(hand, new BlockHitResult(closestVec3d(blockPos), Direction.DOWN, blockPos, true), packetPlace.get());
        });
    }

    private int getYaw(Direction direction) {
        if (direction == null) {
            assert mc.player != null;
            return (int) mc.player.getYaw();
        }
        return switch (direction) {
            case NORTH -> 180;
            case SOUTH -> 0;
            case WEST -> 90;
            case EAST -> -90;
            default -> 90;
        };
    }

    private Direction revert(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.SOUTH;
            case SOUTH -> Direction.NORTH;
            case WEST -> Direction.EAST;
            case EAST -> Direction.WEST;
            default -> Direction.DOWN;
        };
    }

    private BlockPos getRedstonePos(BlockPos blockPos) {
        ArrayList<BlockPos> pos = new ArrayList<>();
        if (isNull(blockPos)) return null;

        for (Direction dir : Direction.values()) {

            if (canPlace(blockPos.offset(dir))) pos.add(blockPos.offset(dir));
        }

        if (pos.isEmpty()) return null;
        pos.sort(Comparator.comparingDouble(PlayerUtils::distanceTo));

        return pos.get(0);
    }

    private BlockPos getPistonPos(BlockPos blockPos) {
        ArrayList<BlockPos> pos = new ArrayList<>();

        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN || dir == Direction.UP) continue;

            boolean canPush = isAir(blockPos.up()) && isAir(blockPos.offset(revert(dir))) && isAir(blockPos.offset(revert(dir)).up());

            if (canPlace(blockPos.offset(dir)) && canPush) {
                pos.add(blockPos.offset(dir));
            }
        }

        if (pos.isEmpty()) return null;

        pos.sort(Comparator.comparingDouble(PlayerUtils::distanceTo));
        direction = getDirection(blockPos, pos.get(0));

        return pos.get(0);
    }


    private Direction getDirection(BlockPos from, BlockPos to) {
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN || dir == Direction.UP) continue;

            if (from.offset(dir).equals(to)) return dir;
        }

        return null;
    }

    private boolean hasNull(BlockPos... blockPoses) {
        for (BlockPos blockPos : blockPoses) {
            if (isNull(blockPos)) return true;
        }

        return false;
    }

    private boolean hasFar(BlockPos... blockPoses) {
        for (BlockPos blockPos : blockPoses) {
            if (distanceTo(closestVec3d(blockPos)) > placeRange.get()) return true;
        }

        return false;
    }

    private Vec3d closestVec3d(BlockPos blockpos) {
        if (blockpos == null) return new Vec3d(0.0, 0.0, 0.0);
        assert mc.player != null;
        double x = MathHelper.clamp((mc.player.getX() - blockpos.getX()), 0.0, 1.0);
        double y = MathHelper.clamp((mc.player.getY() - blockpos.getY()), 0.0, 0.6);
        double z = MathHelper.clamp((mc.player.getZ() - blockpos.getZ()), 0.0, 1.0);
        return new Vec3d(blockpos.getX() + x, blockpos.getY() + y, blockpos.getZ() + z);
    }

    @Override
    public String getInfoString() {
        return target != null ? target.getGameProfile().getName() : null;
    }

    public enum Stage {
        Preparing, Piston, Redstone, ZeroTick, Toggle
    }

    private boolean placeOrder(){
        if(order.get().equals(Order.Redstone)){
            return true;
        }
        return false;
    }
    private SortPriority sort() {
        switch (prio.get()) {
            case Closest:
                return SortPriority.LowestDistance;
            case Furthest:
                return SortPriority.HighestDistance;
            case HighestHealth:
                return SortPriority.HighestHealth;
            case LowestHealth:
                return SortPriority.LowestHealth;
        }
        return SortPriority.LowestDistance;
    }
}