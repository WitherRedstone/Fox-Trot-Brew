package com.chinaex123.fox_trot_brew.maid.behavior;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidCheckRateTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidPathFindingBFS;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.github.ysbbbbbb.kaleidoscopetavern.api.blockentity.IPressingTub;
import com.github.ysbbbbbb.kaleidoscopetavern.crafting.recipe.PressingTubRecipe;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * 女仆操作压榨桶的完整工作流行为。
 *
 * <p>绑定机制：
 * <ul>
 *   <li>女仆第一次认领某个盆时，把坐标记在 {@link #boundPos}，并写入静态 {@link #BOUND}；</li>
 *   <li>之后一直使用这个坐标，不再每次重新搜索；</li>
 *   <li>只有当盆被破坏、超出范围、被别的女仆抢走时，才解绑并重新找；</li>
 *   <li>这样两个女仆各自绑定一个盆，不会互相交换。</li>
 * </ul>
 */
public class MaidPressingTubBehavior extends MaidCheckRateTask {

    /** 一桶流体的容量（mB） */
    private static final int BUCKET = 1000;

    /** 压榨节奏：连续跳多少次后进入休息。 */
    private static final int JUMPS_PER_BURST = 8;
    /** 两次跳跃之间的最小间隔（tick）。 */
    private static final int JUMP_INTERVAL = 12;
    /** 一轮跳完后的休息时长（tick）。 */
    private static final int REST_TICKS = 60;
    /** 单次访问的硬上限（tick）。 */
    private static final long MAX_VISIT_TICKS = 600;
    /** 无进展容忍上限（tick）。 */
    private static final long MAX_IDLE_TICKS = 80;

    /** 完成收液后的桶冷却（tick）。 */
    private static final long COOLDOWN_COLLECTED = 200;
    /** 正常结束但未收获的桶冷却（tick）。 */
    private static final long COOLDOWN_FINISHED = 100;
    /** 中途被打断的桶冷却（tick）。 */
    private static final long COOLDOWN_INTERRUPTED = 120;
    /** 寻路不可达时的桶冷却（tick）。 */
    private static final long COOLDOWN_UNREACHABLE = 100;

    /** 占用超时（tick），防止女仆异常退出后占用不释放。 */
    private static final long CLAIM_TIMEOUT = 600;
    /** 访问记录保留时长（tick）。 */
    private static final long VISIT_MEMORY_TICKS = 12000;

    /** 桶 -> 占用它的女仆 UUID。静态，跨行为实例共享。 */
    private static final Map<BlockPos, UUID> CLAIMS = new HashMap<>();
    /** 桶 -> 占用时间戳，用于超时清理。 */
    private static final Map<BlockPos, Long> CLAIM_TIMES = new HashMap<>();
    /** 桶 -> 上次被访问的时间，静态共享，让“最久未访问优先”跨女仆生效。 */
    private static final Map<BlockPos, Long> LAST_VISITS = new HashMap<>();
    /** 桶 -> 绑定它的女仆 UUID。绑定比占用更持久，直到盆被破坏。 */
    private static final Map<BlockPos, UUID> BOUND = new HashMap<>();

    /**
     * 尝试占用指定桶。
     * <p>
     * 若已被其他女仆占用且未超时，则失败；否则记录占用者与时间戳。
     *
     * @param pos  桶坐标
     * @param maid 女仆
     * @param now  当前游戏时间
     * @return 占用成功返回 true
     */
    private static boolean claim(BlockPos pos, EntityMaid maid, long now) {
        UUID owner = CLAIMS.get(pos);
        if (owner != null && !owner.equals(maid.getUUID())) {
            Long t = CLAIM_TIMES.get(pos);
            if (t != null && now - t < CLAIM_TIMEOUT) {
                return false;
            }
        }
        CLAIMS.put(pos, maid.getUUID());
        CLAIM_TIMES.put(pos, now);
        return true;
    }

    /**
     * 释放指定桶的占用（仅当占用者为当前女仆时）。
     *
     * @param pos  桶坐标
     * @param maid 女仆
     */
    private static void release(BlockPos pos, EntityMaid maid) {
        UUID owner = CLAIMS.get(pos);
        if (owner != null && owner.equals(maid.getUUID())) {
            CLAIMS.remove(pos);
            CLAIM_TIMES.remove(pos);
        }
    }

    /**
     * 判断指定桶是否未被占用或由当前女仆占用。
     *
     * @param pos  桶坐标
     * @param maid 女仆
     * @return 可占用返回 true
     */
    private static boolean isClaimedByMe(BlockPos pos, EntityMaid maid) {
        UUID owner = CLAIMS.get(pos);
        return owner == null || owner.equals(maid.getUUID());
    }

    /**
     * 判断指定桶是否未绑定或由当前女仆绑定。
     *
     * @param pos  桶坐标
     * @param maid 女仆
     * @return 可绑定返回 true
     */
    private static boolean isBoundByMe(BlockPos pos, EntityMaid maid) {
        UUID owner = BOUND.get(pos);
        return owner == null || owner.equals(maid.getUUID());
    }

    /**
     * 将指定桶绑定到当前女仆。
     *
     * @param pos  桶坐标
     * @param maid 女仆
     */
    private static void bind(BlockPos pos, EntityMaid maid) {
        BOUND.put(pos, maid.getUUID());
    }

    /**
     * 解除指定桶与当前女仆的绑定（仅当绑定者为当前女仆时）。
     *
     * @param pos  桶坐标
     * @param maid 女仆
     */
    private static void unbind(BlockPos pos, EntityMaid maid) {
        UUID owner = BOUND.get(pos);
        if (owner != null && owner.equals(maid.getUUID())) {
            BOUND.remove(pos);
        }
    }

    /**
     * 清理超时的占用记录。
     *
     * @param now 当前游戏时间
     */
    private static void cleanupClaims(long now) {
        CLAIM_TIMES.entrySet().removeIf(e -> now - e.getValue() > CLAIM_TIMEOUT);
        CLAIMS.keySet().removeIf(p -> !CLAIM_TIMES.containsKey(p));
    }

    /**
     * 清理过期的访问记录。
     *
     * @param now 当前游戏时间
     */
    private static void cleanupVisits(long now) {
        LAST_VISITS.entrySet().removeIf(e -> now - e.getValue() > VISIT_MEMORY_TICKS);
    }

    /** 移动速度 */
    private final float movementSpeed;

    /** 本女仆自己的短期冷却，避免短时间内重复选中同一个桶。 */
    private final Map<BlockPos, Long> cooldowns = new HashMap<>();

    /** 本女仆绑定的盆坐标。只要有效就一直用它。 */
    private BlockPos boundPos;

    /** 当前缓存的所有压榨配方 */
    private List<PressingTubRecipe> recipes = List.of();

    /** 选定的接近位置（站立点），可能为 null 表示直接走向桶本身 */
    private BlockPos approach;

    /** 开始前往目标的时间 */
    private long travelStarted;
    /** 本次访问开始的时间 */
    private long visitStarted;
    /** 上次检测到进展的时间 */
    private long lastProgress;
    /** 下次允许跳跃的时间 */
    private long nextJump;

    /** 上次记录的果实数量，用于检测进展 */
    private int previousFruit;
    /** 上次记录的流体量，用于检测进展 */
    private int previousFluid;

    /** 本轮已完成的跳跃次数 */
    private int jumpsDone;
    /** 当前休息计时 */
    private int restTimer;

    /** 本次行为是否已完成 */
    private boolean finished;
    /** 本次行为是否完成了有效工作（用于冷却时长判定） */
    private boolean completedWork;
    /** 是否已到达目标附近 */
    private boolean arrived;

    /**
     * 构造女仆压榨桶行为。
     *
     * @param movementSpeed 移动速度
     */
    public MaidPressingTubBehavior(float movementSpeed) {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
                InitEntities.TARGET_POS.get(), MemoryStatus.REGISTERED
        ));
        this.movementSpeed = movementSpeed;
        setMaxCheckRate(20);
    }

    /**
     * 行为动作枚举。
     * <p>
     * NONE 表示无可用动作，COLLECT 表示收集流体，PRESS 表示压榨，FEED 表示投放果实。
     */
    private enum Action { NONE, COLLECT, PRESS, FEED }

    /**
     * 根据桶状态与条件选择动作。
     *
     * @param full       桶内流体是否已满
     * @param canCollect 是否可收集
     * @param canPress   是否可压榨
     * @param canFeed    是否可投放
     * @return 选定的动作
     */
    private static Action choose(boolean full, boolean canCollect, boolean canPress, boolean canFeed) {
        if (full) return canCollect ? Action.COLLECT : Action.NONE;
        if (canPress) return Action.PRESS;
        return canFeed ? Action.FEED : Action.NONE;
    }

    /**
     * 判断本次访问是否超时。
     *
     * @param visitTicks 本次访问已持续的 tick 数
     * @param idleTicks  无进展已持续的 tick 数
     * @return 超时返回 true
     */
    private static boolean expired(long visitTicks, long idleTicks) {
        return visitTicks >= MAX_VISIT_TICKS || idleTicks >= MAX_IDLE_TICKS;
    }

    /**
     * 绑定的盆是否仍然有效：
     * <ul>
     *   <li>坐标非空；</li>
     *   <li>方块实体仍是压榨桶；</li>
     *   <li>仍在自己名下（没被别的女仆抢绑）；</li>
     *   <li>在自己活动范围内。</li>
     * </ul>
     *
     * @param world 服务端世界
     * @param maid  女仆
     * @return 绑定有效返回 true
     */
    private boolean isBoundValid(ServerLevel world, EntityMaid maid) {
        if (boundPos == null) return false;
        if (!world.hasChunkAt(boundPos)) return false;
        if (!(world.getBlockEntity(boundPos) instanceof IPressingTub)) return false;
        if (!isBoundByMe(boundPos, maid)) return false;
        return maid.isWithinRestriction(boundPos);
    }

    /**
     * 检查是否满足额外启动条件。
     * <p>
     * 清理冷却与过期记录，刷新配方缓存，处理绑定失效与重新绑定，
     * 并在已到达目标附近时允许启动。
     *
     * @param world 服务端世界
     * @param maid  女仆
     * @return 可以启动返回 true
     */
    @Override
    protected boolean checkExtraStartConditions(@NotNull ServerLevel world, @NotNull EntityMaid maid) {
        if (!super.checkExtraStartConditions(world, maid)) return false;

        long now = world.getGameTime();
        cooldowns.entrySet().removeIf(e -> e.getValue() <= now);
        cleanupClaims(now);
        cleanupVisits(now);

        recipes = world.getRecipeManager().getRecipes().stream()
                .filter(PressingTubRecipe.class::isInstance)
                .map(PressingTubRecipe.class::cast)
                .toList();

        // 绑定失效：解绑、清占用、重新找
        if (boundPos != null && !isBoundValid(world, maid)) {
            release(boundPos, maid);
            unbind(boundPos, maid);
            cooldowns.put(boundPos, now + COOLDOWN_INTERRUPTED);
            boundPos = null;
            approach = null;
            arrived = false;
            maid.getNavigation().stop();
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }

        // 没有绑定就找一个新盆并绑定
        if (boundPos == null) {
            BlockPos found = findTub(world, maid);
            if (found != null && claim(found, maid, now) && isBoundByMe(found, maid)) {
                boundPos = found;
                bind(found, maid);
                arrived = false;
                travelStarted = now;
            }
        }
        if (boundPos == null) return false;

        // 已经到达绑定盆旁边
        if (horizontalDistance(maid, boundPos) < 1.44
                && Math.abs(maid.getY() - boundPos.getY()) < 1.5) {
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            return true;
        }

        BehaviorUtils.setWalkAndLookTargetMemories(
                maid, approach == null ? boundPos : approach, movementSpeed, 0);
        return false;
    }

    /**
     * 行为启动时初始化各项状态。
     *
     * @param world 服务端世界
     * @param maid  女仆
     * @param time  当前游戏时间
     */
    @Override
    protected void start(ServerLevel world, EntityMaid maid, long time) {
        maid.getNavigation().stop();
        finished = false;
        completedWork = false;
        arrived = true;
        visitStarted = lastProgress = time;
        nextJump = time;
        jumpsDone = 0;
        restTimer = 0;

        if (world.getBlockEntity(boundPos) instanceof IPressingTub tub) {
            remember(tub);
        }
    }

    /**
     * 该行为不使用默认的超时判定，超时由 {@link #canStillUse} 自行管理。
     *
     * @param time 当前游戏时间
     * @return 恒为 false
     */
    @Override
    protected boolean timedOut(long time) {
        return false;
    }

    /**
     * 判断行为是否可以继续执行。
     * <p>
     * 需满足：未完成、绑定有效、占用有效、距离在范围内，
     * 且未超过访问时限或无进展时限。
     *
     * @param world 服务端世界
     * @param maid  女仆
     * @param time  当前游戏时间
     * @return 可以继续返回 true
     */
    @Override
    protected boolean canStillUse(@NotNull ServerLevel world, @NotNull EntityMaid maid, long time) {
        if (finished || boundPos == null) return false;
        if (!isBoundValid(world, maid)) return false;
        if (!isClaimedByMe(boundPos, maid)) return false;
        if (horizontalDistance(maid, boundPos) >= 2.25) return false;
        if (Math.abs(maid.getY() - boundPos.getY()) >= 2.5) return false;

        if (jumpsDone > 0 && jumpsDone < JUMPS_PER_BURST) return true;
        if (jumpsDone >= JUMPS_PER_BURST && restTimer <= REST_TICKS) return true;

        return !expired(time - visitStarted, time - lastProgress);
    }

    /**
     * 每 tick 执行行为逻辑。
     * <p>
     * 检测桶内果实与流体变化以更新进展时间，
     * 并根据当前动作执行收集、投放或压榨。
     *
     * @param world 服务端世界
     * @param maid  女仆
     * @param time  当前游戏时间
     */
    @Override
    protected void tick(ServerLevel world, @NotNull EntityMaid maid, long time) {
        if (!(world.getBlockEntity(boundPos) instanceof IPressingTub tub)) {
            finished = true;
            return;
        }

        int fruitNow = tub.getItems().getStackInSlot(0).getCount();
        int fluidNow = tub.getFluidAmount();
        if (fruitNow != previousFruit || fluidNow != previousFluid) {
            lastProgress = time;
            remember(tub);
        }

        switch (action(world, maid, boundPos)) {
            case COLLECT -> {
                collect(maid, tub);
                completedWork = true;
                finished = true;
            }
            case FEED -> {
                feed(maid, tub);
                finished = !canPress(tub, tub.getItems().getStackInSlot(0));
            }
            case PRESS -> doPress(maid, time);
            case NONE -> finished = true;
        }
    }

    /**
     * 行为结束时清理占用、写入冷却并停止移动。
     * <p>
     * 注意：这里不解除绑定，女仆下次仍会回到这个盆。
     *
     * @param world 服务端世界
     * @param maid  女仆
     * @param time  当前游戏时间
     */
    @Override
    protected void stop(@NotNull ServerLevel world, @NotNull EntityMaid maid, long time) {
        super.stop(world, maid, time);
        if (boundPos != null) {
            release(boundPos, maid);
            long cd;
            if (completedWork) {
                cd = COOLDOWN_COLLECTED;
            } else if (finished) {
                cd = COOLDOWN_FINISHED;
            } else {
                cd = COOLDOWN_INTERRUPTED;
            }
            cooldowns.put(boundPos, time + cd);
            LAST_VISITS.put(boundPos, time);
            // 注意：这里不 unbind。绑定保留，女仆下次还回到这个盆。
        }
        approach = null;
        arrived = false;
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    /**
     * 执行压榨动作：向桶中心靠拢并按节奏跳跃。
     *
     * @param maid 女仆
     * @param time 当前游戏时间
     */
    private void doPress(EntityMaid maid, long time) {
        double dx = boundPos.getX() + 0.5 - maid.getX();
        double dz = boundPos.getZ() + 0.5 - maid.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq > 0.01) {
            double factor = distSq > 0.25 ? 0.1 : 0.05;
            maid.setDeltaMovement(dx * factor, maid.getDeltaMovement().y, dz * factor);
        } else {
            maid.setDeltaMovement(0, maid.getDeltaMovement().y, 0);
        }

        if (jumpsDone >= JUMPS_PER_BURST) {
            if (restTimer < REST_TICKS) {
                restTimer++;
                return;
            }
            jumpsDone = 0;
            restTimer = 0;
            nextJump = time;
            lastProgress = time;
            return;
        }

        if (time < nextJump) return;

        if (maid.onGround()) {
            maid.setDeltaMovement(maid.getDeltaMovement().add(0, 0.42, 0));
            jumpsDone++;
            nextJump = time + JUMP_INTERVAL;
        }
    }

    /**
     * 记录当前桶内的果实与流体数量，用于进展检测。
     *
     * @param tub 压榨桶
     */
    private void remember(IPressingTub tub) {
        previousFruit = tub.getItems().getStackInSlot(0).getCount();
        previousFluid = tub.getFluidAmount();
    }

    /**
     * 计算女仆与桶中心的水平距离平方。
     *
     * @param maid 女仆
     * @param pos  桶坐标
     * @return 水平距离平方
     */
    private static double horizontalDistance(EntityMaid maid, BlockPos pos) {
        return maid.distanceToSqr(pos.getX() + 0.5, maid.getY(), pos.getZ() + 0.5);
    }

    /**
     * 判断指定位置是否允许作为目标（在范围内、区块已加载、方块实体为压榨桶）。
     *
     * @param world 服务端世界
     * @param maid  女仆
     * @param pos   待判断坐标
     * @return 允许返回 true
     */
    private boolean allowed(ServerLevel world, EntityMaid maid, BlockPos pos) {
        return maid.isWithinRestriction(pos)
                && world.hasChunkAt(pos)
                && world.getBlockEntity(pos) instanceof IPressingTub;
    }

    /**
     * 在女仆活动范围内搜索可用的压榨桶。
     * <p>
     * 优先选择可收集的桶，其次为可工作的桶；
     * 排序依据为最久未访问优先，其次为距离最近。
     * 同时使用寻路 BFS 验证可达性，并记录接近位置。
     *
     * @param world 服务端世界
     * @param maid  女仆
     * @return 找到的桶坐标，未找到返回 null
     */
    private BlockPos findTub(ServerLevel world, EntityMaid maid) {
        BlockPos center = maid.hasRestriction() ? maid.getRestrictCenter() : maid.blockPosition();
        int range = maid.hasRestriction() ? (int) maid.getRestrictRadius() : 16;

        List<BlockPos> collect = new ArrayList<>();
        List<BlockPos> work = new ArrayList<>();

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-range, -4, -range),
                center.offset(range, 4, range))) {
            if (cooldowns.containsKey(pos) || !allowed(world, maid, pos)) continue;
            if (!isClaimedByMe(pos, maid)) continue;
            if (!isBoundByMe(pos, maid)) continue;
            Action action = action(world, maid, pos);
            if (action == Action.COLLECT) {
                collect.add(pos.immutable());
            } else if (action != Action.NONE) {
                work.add(pos.immutable());
            }
        }

        Comparator<BlockPos> nearest = Comparator
                .comparingLong((BlockPos p) -> LAST_VISITS.getOrDefault(p, Long.MIN_VALUE))
                .thenComparingDouble(p -> p.distToCenterSqr(maid.position()));

        collect.sort(nearest);
        work.sort(nearest);
        collect.addAll(work);

        MaidPathFindingBFS paths = new MaidPathFindingBFS(
                maid.getNavigation().getNodeEvaluator(), world, maid);
        try {
            for (BlockPos pos : collect) {
                Action next = action(world, maid, pos);
                List<BlockPos> feet = new ArrayList<>();
                // 压榨时直接站在桶旁；其他动作优先站在桶的四周
                if (next != Action.PRESS) {
                    feet.add(pos.north());
                    feet.add(pos.south());
                    feet.add(pos.east());
                    feet.add(pos.west());
                }
                feet.add(pos.above());
                feet.add(pos);
                feet.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(maid.position())));

                for (BlockPos foot : feet) {
                    if (maid.isWithinRestriction(foot) && paths.canPathReach(foot)) {
                        approach = foot;
                        return pos;
                    }
                }
                cooldowns.put(pos, world.getGameTime() + COOLDOWN_UNREACHABLE);
            }
        } finally {
            paths.finish();
        }
        return null;
    }

    /**
     * 根据桶的当前状态与女仆物品栏判断应执行的动作。
     *
     * @param world 服务端世界
     * @param maid  女仆
     * @param pos   桶坐标
     * @return 选定的动作
     */
    private Action action(ServerLevel world, EntityMaid maid, BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof IPressingTub tub)) {
            return Action.NONE;
        }
        IItemHandler inventory = workInventory(maid);
        boolean full = tub.getFluidAmount() >= BUCKET;
        return choose(
                full,
                full && bucketSlot(inventory, tub) >= 0,
                !full && canPress(tub, tub.getItems().getStackInSlot(0)),
                !full && feedSlot(inventory, tub) >= 0
        );
    }

    /**
     * 判断当前果实是否可压榨，且产出流体能完全装入桶中。
     *
     * @param tub   压榨桶
     * @param fruit 果实物品
     * @return 可压榨返回 true
     */
    private boolean canPress(IPressingTub tub, ItemStack fruit) {
        if (fruit.isEmpty()) return false;
        for (PressingTubRecipe recipe : recipes) {
            if (recipe.getIngredient().test(fruit)) {
                int amount = recipe.getFluidAmount();
                return amount > 0
                        && tub.getFluid().fill(
                        new FluidStack(recipe.getFluid(), amount),
                        IFluidHandler.FluidAction.SIMULATE) == amount;
            }
        }
        return false;
    }

    /**
     * 在女仆物品栏中查找可投放的果实槽位。
     *
     * @param inventory 物品栏
     * @param tub       压榨桶
     * @return 可用槽位索引，未找到返回 -1
     */
    private int feedSlot(IItemHandler inventory, IPressingTub tub) {
        if (!tub.getItems().getStackInSlot(0).isEmpty()) return -1;
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack fruit = inventory.extractItem(i, 1, true);
            if (canPress(tub, fruit)
                    && tub.getItems().insertItem(0, fruit, true).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 执行投放动作：从物品栏取出果实并放入桶中。
     *
     * @param maid 女仆
     * @param tub  压榨桶
     */
    private void feed(EntityMaid maid, IPressingTub tub) {
        IItemHandler inventory = workInventory(maid);
        int slot = feedSlot(inventory, tub);
        if (slot < 0) return;

        ItemStack offered = inventory.extractItem(slot, tub.getItems().getSlotLimit(0), true);
        int accepted = offered.getCount()
                - tub.getItems().insertItem(0, offered, true).getCount();
        if (accepted <= 0) return;

        ItemStack fruit = inventory.extractItem(slot, accepted, false);
        tub.addIngredient(fruit);
        returnToBackpack(maid, inventory, fruit);
        maid.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * 在女仆物品栏中查找空桶槽位。
     *
     * @param inventory 物品栏
     * @param tub       压榨桶
     * @return 可用槽位索引，未找到返回 -1
     */
    private int bucketSlot(IItemHandler inventory, IPressingTub tub) {
        if (tub.getFluidAmount() <= 0) return -1;
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (inventory.extractItem(i, 1, true).is(Items.BUCKET)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 执行收集动作：用空桶收取桶内流体。
     *
     * @param maid 女仆
     * @param tub  压榨桶
     */
    private void collect(EntityMaid maid, IPressingTub tub) {
        IItemHandler inventory = workInventory(maid);
        int slot = bucketSlot(inventory, tub);
        if (slot < 0) return;

        ItemStack empty = inventory.extractItem(slot, 1, false);
        if (empty.isEmpty()) return;

        ItemStack template = new ItemStack(Items.BUCKET);
        if (!tub.getResult(maid, template)) {
            returnToBackpack(maid, inventory, empty);
            return;
        }

        if (!ItemHandlerHelper.insertItemStacked(inventory, template, true).isEmpty()) {
            returnToBackpack(maid, inventory, empty);
            return;
        }

        returnToBackpack(maid, inventory, template);
        maid.swing(InteractionHand.MAIN_HAND);
    }

    /**
     * 获取女仆的工作物品栏（背包与手持物品的组合包装）。
     *
     * @param maid 女仆
     * @return 工作物品栏
     */
    private IItemHandler workInventory(EntityMaid maid) {
        return new CombinedInvWrapper(
                maid.getAvailableBackpackInv(),
                maid.getHandsInvWrapper());
    }

    /**
     * 将物品放回女仆背包，背包满时掉落到世界中。
     *
     * @param maid      女仆
     * @param inventory 物品栏
     * @param itemStack 待放回的物品
     */
    private void returnToBackpack(EntityMaid maid, IItemHandler inventory, ItemStack itemStack) {
        if (itemStack.isEmpty()) return;
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, itemStack, false);
        if (!remainder.isEmpty()) maid.spawnAtLocation(remainder);
    }
}