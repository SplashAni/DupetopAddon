package dupetop.addon.modules;

import dupetop.addon.Main;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;

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

    private final SettingGroup sgRender = settings.createGroup("Render");


    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder().name("target-range").description("Range in which to target players.").defaultValue(6).sliderRange(0, 10).build());
    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder().name("place-range").description("Range in which to place blocks.").defaultValue(4.6).sliderRange(0, 7).build());
    private final Setting<Prioity> prio = sgGeneral.add(new EnumSetting.Builder<Prioity>().name("priority-distance").description("Which target to prioritize").defaultValue(Prioity.Closest).build());
    private final Setting<Order> order = sgGeneral.add(new EnumSetting.Builder<Order>().name("place-order").description("The order to place blocks.").defaultValue(Order.Piston).build());


    //  soon tm  private final Setting<Boolean> holeFill = sgGeneral.add(new BoolSetting.Builder().name("hole-fill").description("Places obsidian inside of the target block.").defaultValue(true).build());
    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder().name("swap-back").description("Automatically swaps to previous slot.").defaultValue(true).build());
    private final Setting<Boolean> instant = sgGeneral.add(new BoolSetting.Builder().name("instant").description("Does everything instantly.").defaultValue(true).build());
    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder().name("debug").description("Debugging").defaultValue(true).build());


    // PAUSE
    private final Setting<Boolean> pause = sgPause.add(new BoolSetting.Builder().name("pause").description("").defaultValue(true).build());

    private final Setting<Boolean> eatPause = sgPause.add(new BoolSetting.Builder().name("pause-on-eat").description("Pauses if player is eating.").defaultValue(true).visible(pause::get).build());

    private final Setting<Boolean> drinkPause = sgPause.add(new BoolSetting.Builder().name("pause-on-drink").description("Pauses if drinking.").defaultValue(false).visible(pause::get).build());
    private final Setting<Boolean> placePause = sgPause.add(new BoolSetting.Builder().name("place-on-pause").description("Pauses if placing.").defaultValue(false).visible(pause::get).build());

    //RENDER
    private final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder().name("swing").description("Whether to swing on place.").defaultValue(true).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the shapes are rendered.")
            .defaultValue(ShapeMode.Both)
            .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
            .name("side-color")
            .description("The side color of the rendering.")
            .defaultValue(new SettingColor(225, 0, 0, 75))
            .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
            .name("line-color")
            .description("The line color of the rendering.")
            .defaultValue(new SettingColor(225, 0, 0, 255))
            .build()
    );


    public PistonPush() {
        super(Main.CATEGORY, "piston-push", "Pushes players out of holes");
    }

    private BlockPos pistonPos;
    private BlockPos redstonePos;
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
        stage = Stage.Preparing;
    }

    @Override
    public void onDeactivate() {
        pistonPos = null;
        redstonePos = null;
        direction = null;
        super.onDeactivate();
    }

    @EventHandler
    public void onTick(TickEvent.Post event) {
        target = getPlayerTarget(targetRange.get(), sort());
        if (isBadTarget(target, targetRange.get())) {
            info("No targets found, toggling...");
            toggle();
            return;
        }

        if (!findInHotbar(Items.PISTON, Items.STICKY_PISTON).found() || !findInHotbar(Items.REDSTONE_BLOCK).found()) {
            info("Items not found, togging...");
            toggle();
            return;
        }

        if (pause.get() && PlayerUtils.shouldPause(placePause.get(), eatPause.get(), drinkPause.get())) return;

        switch (stage) {
            case Preparing -> {
                assert mc.player != null;
                prevSlot = mc.player.getInventory().selectedSlot;

                BlockPos targetPos = getBlockPos(target).up();
                pistonPos = canPiston(targetPos);
                redstonePos = getRedstonePos(pistonPos);

                if (hasNull(targetPos, pistonPos, redstonePos)) stage = Stage.Toggle;
                if (hasFar(targetPos, pistonPos, redstonePos)) stage = Stage.Toggle;


                stage = instant.get() ? Stage.Instant : (placeOrder() ? Stage.Redstone : Stage.Piston);
            }
            case Piston -> {
                place(findInHotbar(Items.PISTON, Items.STICKY_PISTON), pistonPos);
                stage = Stage.Redstone;
            }
            case Redstone -> {
                place(findInHotbar(Items.REDSTONE_BLOCK), redstonePos);
                stage = Stage.Piston;
            }
            case Instant -> {
                place(findInHotbar(Items.PISTON, Items.STICKY_PISTON), pistonPos);
                place(findInHotbar(Items.REDSTONE_BLOCK), redstonePos);
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

    private void place(FindItemResult result, BlockPos blockPos) {
        if (isNull(blockPos)) return;
        Hand hand = result.isOffhand() ? Hand.OFF_HAND : Hand.MAIN_HAND;

        Rotations.rotate(getYaw(direction), 0, () -> {
            updateSlot(result, true);
            placeBlock(hand, new BlockHitResult(closestVec3d(blockPos), Direction.DOWN, blockPos, true), swing.get());

            if (debug.get()) {
               info("Attempted to push "+target.getGameProfile().getName());
            }

        });
    }

    private BlockPos canPiston(BlockPos blockPos) {
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

    @Override
    public String getInfoString() {
        return target != null ? target.getGameProfile().getName() : null;
    }

    public enum Stage {
        Preparing, Piston, Redstone, Instant, Toggle
    }

    @EventHandler
    public void onRender(Render3DEvent event) {
        if(pistonPos == null || redstonePos == null) return;

        event.renderer.box(pistonPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        event.renderer.box(redstonePos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);

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