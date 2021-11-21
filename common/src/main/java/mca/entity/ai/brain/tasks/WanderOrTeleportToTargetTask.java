package mca.entity.ai.brain.tasks;

import mca.entity.ai.MemoryModuleTypeMCA;
import mca.util.compat.FuzzyPositionsCompat;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.ai.brain.task.WanderAroundTask;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class WanderOrTeleportToTargetTask extends WanderAroundTask {

    private static final double TELEPORT_LIMIT_SQ = Math.pow(100, 2);

    public WanderOrTeleportToTargetTask() { }

    public WanderOrTeleportToTargetTask(int minRunTime, int maxRunTime) {
       super(minRunTime, maxRunTime);
    }

    @Override
    protected boolean shouldRun(ServerWorld serverWorld, MobEntity mobEntity) {
        return super.shouldRun(serverWorld, mobEntity)
                && mobEntity.getBrain().isMemoryInState(MemoryModuleTypeMCA.PLAYER_FOLLOWING, MemoryModuleState.VALUE_ABSENT)
                && mobEntity.getBrain().isMemoryInState(MemoryModuleTypeMCA.STAYING, MemoryModuleState.VALUE_ABSENT);
    }

    @Override
    protected void keepRunning(ServerWorld world, MobEntity entity, long l) {
        Brain<?> brain = entity.getBrain();
        WalkTarget walkTarget = brain.getOptionalMemory(MemoryModuleType.WALK_TARGET).get();

        BlockPos targetPos = walkTarget.getLookTarget().getBlockPos();

        if (targetPos.getSquaredDistance(entity.getBlockPos()) > TELEPORT_LIMIT_SQ) {
            // If the target is more than 100 blocks away, teleport to it immediately.
            // The target location is fuzzed and then adjusted to ensure the entity doesn't land in any walls.
            Vec3d pos = Vec3d.ofBottomCenter(
                    FuzzyPositionsCompat.upWhile(targetPos
                            .add(FuzzyPositionsCompat.localFuzz(world.random, 5, 0)
                    ), world.getHeight(), p -> world.getBlockState(p).shouldSuffocate(world, p))
            );

            entity.requestTeleport(pos.getX(), pos.getY(), pos.getZ());
        }

       super.keepRunning(world, entity, l);
    }
 }
