package dupetop.addon.modules;

import dupetop.addon.Addon;
import dupetop.addon.utils.TimerUtils;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import static dupetop.addon.utils.TimerUtils.closestVec3d;
import static meteordevelopment.meteorclient.utils.player.InvUtils.findFastestTool;
import static meteordevelopment.meteorclient.utils.player.InvUtils.findInHotbar;
import static meteordevelopment.meteorclient.utils.player.PlayerUtils.distanceTo;

public class PistonAura extends Module {
    private static int prevSlot;

    public enum Distance {
        Closest, Highest
    }

    public enum Break {
        Packet, Client
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgNone = settings.createGroup("");

    private final Setting<Integer> targetRange = sgGeneral.add(new IntSetting.Builder().name("target-range").description("Range for working module.").defaultValue(5).min(0).sliderMax(7).build());
    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder().name("place-range").description("Range for placing blocks.").defaultValue(4.5).min(1).sliderMax(7).build());
    private final Setting<Distance> distance = sgGeneral.add(new EnumSetting.Builder<Distance>().name("distance").defaultValue(Distance.Closest).build());
    private final Setting<Break> doBreak = sgGeneral.add(new EnumSetting.Builder<Break>().name("distance").defaultValue(Break.Packet).build());
    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder().name("action-delay").description("Delay between actions.").defaultValue(120).sliderMin(120).sliderMax(300).build());
    private final Setting<Integer> delayedAttack = sgGeneral.add(new IntSetting.Builder().name("delayed-attack").description("Delayed attack.").defaultValue(25).sliderMin(25).sliderMax(100).build());
    private final Setting<Boolean> strictDirection = sgGeneral.add(new BoolSetting.Builder().name("strict-direction").description("Places only in forward direction.").defaultValue(false).build());
    private final Setting<Boolean> allowUpper = sgGeneral.add(new BoolSetting.Builder().name("allow-upper").description("Allows placing blocks above.").defaultValue(false).build());
    private final Setting<Boolean> trap = sgGeneral.add(new BoolSetting.Builder().name("trap").description("Places obsidian above target.").defaultValue(false).build());
    private final Setting<Boolean> toggleOnJump = sgGeneral.add(new BoolSetting.Builder().name("toggle-on-jump").description("Automatically toggles off on jump.").defaultValue(false).build());
    private final Setting<Boolean> pauseOnEat = sgGeneral.add(new BoolSetting.Builder().name("pause-on-eat").description("Stops Piston Aura when you eating.").defaultValue(false).build());
    private final Setting<Boolean> pauseOnCA = sgGeneral.add(new BoolSetting.Builder().name("pause-on-CA").description("Stops Crystal Aura when Piston Aura works.").defaultValue(false).build());



    public PistonAura() {
        super(Addon.CATEGORY, "piston-aura", "Automatically pushing crystals into enemy by piston.");
        get = this;
    }

    private PlayerEntity target;
    private BlockPos crystalPos, pistonPos, blockPos, trapPos;
    private Direction direction;

    private boolean shouldBreak;
    private EndCrystalEntity endCrystal;

    private Stage stage;

    private final TimerUtils timer = new TimerUtils();
    private Positions.Triplet stacked = null;

    @Override
    public void onActivate() {
        crystalPos = null;
        pistonPos = null;
        blockPos = null;
        trapPos = null;

        shouldBreak = true;
        endCrystal = null;

        stage = Stage.Preparing;

        timer.reset();
        stacked = null;
    }

    @EventHandler
    public void onAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof EndCrystalEntity)) return;
        if (crystalPos == null) return;

        if (crystalPos.equals(event.entity.getBlockPos())) this.endCrystal = (EndCrystalEntity) event.entity;
    }

    @EventHandler
    public void onRemove(EntityRemovedEvent event) {
        if (this.endCrystal == null) return;

        if (event.entity.equals(this.endCrystal)) this.endCrystal = null;
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        target = TargetUtils.getPlayerTarget(targetRange.get(), SortPriority.LowestDistance);
        if (TargetUtils.isBadTarget(target, targetRange.get())) {
            toggle();
            return;
        }

        if (pauseOnEat.get() && mc.player.isUsingItem() && (mc.player.getMainHandStack().isFood() || mc.player.getOffHandStack().isFood())) return;
        if (toggleOnJump.get() && ((mc.options.jumpKey.isPressed() || mc.player.input.jumping) || mc.player.prevY < mc.player.getPos().getY())) {
            toggle();
            return;
        }

        doCheck();
        doRotate();
        switch (stage) {
            case Preparing -> {
                if (getPositions(target).direction == Direction.DOWN) {
                    stacked = getPositions(target);
                    return;
                }

                crystalPos = getPositions(target).blockPos.get(0);
                direction = getPositions(target).direction;

                pistonPos = getPositions(target).blockPos.get(1);
                blockPos = getPositions(target).blockPos.get(2);
                trapPos = target.getBlockPos().up(2);

                if (trap.get() && canPlace(trapPos, false)) doPlace(findInHotbar(Items.OBSIDIAN), trapPos);

                stacked = null;
                stage = Stage.Piston;
            }
            case Piston -> {
                if (!timer.passedMs(actionDelay.get())) return;

                doPlace(findInHotbar(Items.PISTON, Items.STICKY_PISTON), pistonPos);
                nextStage(Stage.Crystal);
            }
            case Crystal -> {
                if (!timer.passedMs(actionDelay.get())) return;

                doPlace(findInHotbar(Items.END_CRYSTAL), crystalPos.down());
                nextStage(Stage.Block);
            }
            case Block -> {
                if (!timer.passedMs(actionDelay.get())) return;

                doPlace(findInHotbar(Items.REDSTONE_BLOCK), blockPos);
                nextStage(Stage.Attack);
            }
            case Attack -> {
                if (doBreak.get() == Break.Packet) doBreak(blockPos);
                if (!timer.passedMs(actionDelay.get() + delayedAttack.get())) return;

                doAttack(endCrystal);
                if (this.endCrystal == null) nextStage(Stage.BreakBlock);
            }
            case BreakBlock -> {
                if (doBreak.get() == Break.Client) doBreak(blockPos);
                InvUtils.swap((findFastestTool(mc.world.getBlockState(blockPos))).slot(), false);
                mc.player.swingHand(Hand.MAIN_HAND);

                if (canPlace(blockPos, true)) {
                    shouldBreak = true;

                    if (Positions.isUpper()) nextStage(Stage.BreakPiston);
                    else nextStage(Stage.Preparing);
                }
            }
            case BreakPiston -> {
                doBreak(pistonPos);
                InvUtils.swap((findFastestTool(mc.world.getBlockState(pistonPos))).slot(), false);
                mc.player.swingHand(Hand.MAIN_HAND);

                if (canPlace(pistonPos, false)) {
                    shouldBreak = true;

                    nextStage(Stage.Preparing);
                }
            }
        }
    }

    private void nextStage(Stage stage) {
        this.stage = stage;
        timer.reset();
    }

    private void doPlace(FindItemResult itemResult, BlockPos blockPos) {
        if (blockPos == null) return;
        if (!itemResult.found()) return;
        Hand hand = itemResult.isOffhand() ? Hand.OFF_HAND : Hand.MAIN_HAND;

        if (!itemResult.isOffhand()) mc.player.getInventory().selectedSlot = itemResult.slot();
        mc.interactionManager.interactBlock(mc.player, hand, new BlockHitResult(closestVec3d(blockPos), Direction.DOWN, blockPos, false));
        mc.player.swingHand(hand);
    }

    private void doBreak(BlockPos blockPos) {
        if (!canBreak(blockPos)) return;

        if (doBreak.get() == Break.Packet) {
            if (!shouldBreak) return;

            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, blockPos, Direction.DOWN));
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, Direction.DOWN));
            shouldBreak = false;
        } else mc.interactionManager.updateBlockBreakingProgress(blockPos, Direction.DOWN);
    }

    private void doAttack(EndCrystalEntity endCrystal) {
        if (endCrystal == null) return;

        mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(endCrystal, mc.player.isSneaking()));
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void doRotate() {
        if (this.direction == null) return;

        Rotations.rotate(strictDirection.get() ? mc.player.getYaw() : getYaw(this.direction), 0);
    }

    private int getYaw(Direction direction) {
        if (direction == null) return (int) mc.player.getYaw();

        return switch (direction) {
            case NORTH -> 180;
            case SOUTH -> 0;
            case WEST -> 90;
            case EAST -> -90;
            default -> throw new IllegalStateException("Unexpected value: " + direction);
        };
    }

    private void doCheck() {
        if (!findInHotbar(Items.END_CRYSTAL).found() || !findInHotbar(Items.PISTON, Items.STICKY_PISTON).found() || !findInHotbar(Items.REDSTONE_BLOCK).found() || !findInHotbar(Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE).found()) {
            info(name, "Can't find required items, toggling...");
            toggle();
            return;
        }
    }

    private Positions.Triplet getPositions(PlayerEntity target) {
        List<Positions.Triplet> init = new Positions().init(target);

        for (Positions.Triplet triplet : init) {
            if (canPlace(triplet.blockPos)) return triplet;
        }

        return new Positions.Triplet(init.get(0).blockPos, Direction.DOWN);
    }

    private boolean canCrystal(BlockPos blockPos) {
        if (hasEntity(new Box(blockPos))) return false;

        return mc.world.isAir(blockPos) &&
                (mc.world.getBlockState(blockPos.down()).isOf(Blocks.OBSIDIAN) || mc.world.getBlockState(blockPos.down()).isOf(Blocks.BEDROCK));
    }

    private boolean canBreak(BlockPos blockPos) {
        return !mc.world.isAir(blockPos);
    }

    private boolean canPlace(List<BlockPos> blockPoses) {
        for (BlockPos blockPos : blockPoses) {
            if (!canCrystal(blockPoses.get(0))) return false;
            if (!canPlace(blockPos, false)) return false;
            if (distanceTo(closestVec3d(blockPos)) > placeRange.get()) return false;
        }

        return true;
    }

    private boolean canPlace(BlockPos blockPos, boolean ignoreEntity) {
        if (blockPos == null) return false;
        if (!mc.world.isAir(blockPos)) return false;
        if (ignoreEntity) return true;

        return !hasEntity(new Box(blockPos), entity -> entity instanceof PlayerEntity || entity instanceof EndCrystalEntity || entity instanceof TntEntity);
    }

    public boolean shouldPause() {
        return isActive() && pauseOnCA.get();
    }


    public enum Stage {
        Preparing, Piston, Crystal, Block, Attack, BreakBlock, BreakPiston
    }

    public static PistonAura get;

    public class Positions {
        private final List<Triplet> positions = new ArrayList<>();
        private static BlockPos main;

        private List<Triplet> init(PlayerEntity target) {
            positions.clear();
            main = target.getBlockPos().up(canUpper(target) ? 2 : 1);

            for (Direction direction : Direction.values()) {
                if (get.strictDirection.get() && mc.player.getHorizontalFacing() != direction) continue;
                if (direction == Direction.UP || direction == Direction.DOWN) continue;
                Direction[] sideDir = sideDirection(direction);

                add(main.offset(direction), main.offset(direction, 2), main.offset(direction, 3), direction);

                add(main.offset(direction), main.offset(direction, 2).offset(sideDir[0]), main.offset(direction, 3).offset(sideDir[0]), main.offset(direction).offset(sideDir[0]), direction);
                add(main.offset(direction), main.offset(direction, 2).offset(sideDir[1]), main.offset(direction, 3).offset(sideDir[1]), main.offset(direction).offset(sideDir[1]), direction);
            }

            Comparator<Triplet> comparator = Comparator.comparingDouble(Triplet::distance);
            return positions.stream().sorted(get.distance.get() == Distance.Closest ? comparator : comparator.reversed()).toList();
        }

        public static boolean isUpper() {
            return main.equals(get.target.getBlockPos().up(2)) && !get.canPlace(get.trapPos, true);
        }

        public static boolean canUpper(PlayerEntity target) {
            return get.allowUpper.get() && (!facePlace(target) || (get.stacked != null && get.stacked.blockPos.get(0).getY() == target.getBlockPos().getY() + 1 && get.stacked.direction == Direction.DOWN));
        }

        public static boolean facePlace(PlayerEntity target) {
            if (target == null) return false;
            BlockPos targetPos = target.getBlockPos().up();

            for (Direction direction : Direction.values()) {
                if (get.canCrystal(targetPos.offset(direction))) return true;
            }

            return false;
        }

        private Direction[] sideDirection(Direction direction) {
            if (direction == Direction.WEST || direction == Direction.EAST) {
                return new Direction[]{Direction.NORTH, Direction.SOUTH};
            } else {
                return new Direction[]{Direction.WEST, Direction.EAST};
            }
        }

        private void add(BlockPos p1, BlockPos p2, BlockPos p3, Direction d1) {
            positions.add(new Triplet(List.of(p1, p2, p3), d1));
        }

        private void add(BlockPos p1, BlockPos p2, BlockPos p3, BlockPos p4, Direction d1) {
            positions.add(new Triplet(List.of(p1, p2, p3, p4), d1));
        }

        public static class Triplet {
            List<BlockPos> blockPos;
            Direction direction;

            public Triplet(List<BlockPos> blockPos, Direction direction) {
                this.blockPos = blockPos;
                this.direction = direction;
            }

            public double distance() {
                return distanceTo(closestVec3d(blockPos.get(2)));
            }

        }
    }
    public static BlockPos getBlockPos(PlayerEntity entity) {
        return entity.getBlockPos();
    }

    public boolean isAir(BlockPos block) {
        return mc.world.getBlockState(block).isAir();
    }

    public void placeBlock(Hand hand, BlockHitResult result, boolean packetPlace) {
        if (packetPlace) mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(hand, result,1));
        else mc.interactionManager.interactBlock(mc.player, hand, result);
    }
    public void updateSlot(FindItemResult result, boolean packet) {
        updateSlot(result.slot(), packet);
    }
    public boolean updateSlot(int slot, boolean packet) {
        if (slot < 0 || slot > 8) return false;
        if (prevSlot == -1) prevSlot = mc.player.getInventory().selectedSlot;

        // updates slot on client and server side
        mc.player.getInventory().selectedSlot = slot;
        if (packet) mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        return true;
    }
    public static boolean hasEntity(Box box) {
        return hasEntity(box, entity -> entity instanceof PlayerEntity || entity instanceof EndCrystalEntity || entity instanceof TntEntity);
    }

    public static boolean hasEntity(Box box, Predicate<Entity> predicate) {
        return EntityUtils.intersectsWithEntity(box, predicate);
    }
}