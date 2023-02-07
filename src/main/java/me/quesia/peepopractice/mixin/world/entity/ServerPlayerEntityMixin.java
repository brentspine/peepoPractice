package me.quesia.peepopractice.mixin.world.entity;

import com.mojang.authlib.GameProfile;
import com.redlimerl.speedrunigt.timer.InGameTimer;
import com.redlimerl.speedrunigt.timer.TimerStatus;
import me.quesia.peepopractice.PeepoPractice;
import me.quesia.peepopractice.core.category.CategoryPreference;
import me.quesia.peepopractice.core.category.PracticeCategories;
import me.quesia.peepopractice.core.category.properties.event.ChangeDimensionSplitEvent;
import me.quesia.peepopractice.core.category.properties.event.SplitEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec2f;
import net.minecraft.world.GameMode;
import net.minecraft.world.PortalForcer;
import net.minecraft.world.World;
import net.minecraft.world.level.LevelProperties;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin extends PlayerEntity {
    @Shadow public abstract void refreshPositionAfterTeleport(double x, double y, double z);
    @Shadow @Nullable public abstract BlockPos getSpawnPointPosition();
    @Shadow public abstract void setGameMode(GameMode gameMode);
    @Shadow public abstract void sendMessage(Text message, boolean actionBar);
    private Long comparingTime;

    public ServerPlayerEntityMixin(World world, BlockPos blockPos, GameProfile gameProfile) {
        super(world, blockPos, gameProfile);
    }

    @Inject(method = "moveToSpawn", at = @At("HEAD"), cancellable = true)
    private void customSpawn(ServerWorld world, CallbackInfo ci) {
        if (PeepoPractice.CATEGORY.hasSplitEvent()) {
            String compareType = CategoryPreference.getValue(PeepoPractice.CATEGORY, "compare_type", "PB");
            switch (compareType) {
                case "PB":
                    this.comparingTime = PeepoPractice.CATEGORY.getSplitEvent().hasPb() ? PeepoPractice.CATEGORY.getSplitEvent().getPbLong() : null;
                    break;
                case "Average":
                    this.comparingTime = PeepoPractice.CATEGORY.getSplitEvent().hasCompletedTimes() ? PeepoPractice.CATEGORY.getSplitEvent().findAverage() : null;
                    break;
            }
        }

        if (PeepoPractice.CATEGORY.hasPlayerProperties() && PeepoPractice.CATEGORY.getPlayerProperties().hasSpawnPos()) {
            if (!(world.getLevelProperties() instanceof LevelProperties)) { return; }

            PeepoPractice.CATEGORY.getPlayerProperties().reset(new Random(world.getSeed()), world);

            if (PeepoPractice.CATEGORY.hasWorldProperties() && PeepoPractice.CATEGORY.getWorldProperties().hasWorldRegistryKey() && PeepoPractice.CATEGORY.getWorldProperties().getWorldRegistryKey().equals(World.END)) {
                ServerWorld.createEndSpawnPlatform(world);
            }

            ((LevelProperties) world.getLevelProperties()).setSpawnPos(PeepoPractice.CATEGORY.getPlayerProperties().getSpawnPos());

            float yaw = 0.0F;
            float pitch = 0.0F;
            if (PeepoPractice.CATEGORY.getPlayerProperties().hasSpawnAngle()) {
                Vec2f spawnAngle = PeepoPractice.CATEGORY.getPlayerProperties().getSpawnAngle();
                yaw = spawnAngle.x;
                pitch = spawnAngle.y;
            }

            BlockPos spawnPos = PeepoPractice.CATEGORY.getPlayerProperties().getSpawnPos();
            this.refreshPositionAndAngles(spawnPos, yaw, pitch);

            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void setComparisonMessage(CallbackInfo ci) {
        long igt = InGameTimer.getInstance().getInGameTime();
        if (this.comparingTime != null) {
            long difference = this.comparingTime - igt;
            boolean flip = false;
            if (difference < 0) {
                difference = igt - this.comparingTime;
                flip = true;
            }
            String timeString = Formatting.GRAY + "Pace: ";
            timeString += !flip ? Formatting.GREEN + "+" : Formatting.RED + "-";
            timeString += SplitEvent.getTimeString(difference);
            this.sendMessage(new LiteralText(timeString), true);
        } else {
            this.sendMessage(new LiteralText(Formatting.GRAY + "Pace: " + Formatting.GREEN + "+" + SplitEvent.getTimeString(igt)), true);
        }
    }

    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
    private void triggerSplitEvent(ServerWorld destination, CallbackInfoReturnable<Entity> cir) {
        if (PeepoPractice.CATEGORY.hasSplitEvent()) {
            if (PeepoPractice.CATEGORY.getSplitEvent() instanceof ChangeDimensionSplitEvent) {
                if (InGameTimer.getInstance().isCompleted() && this.getScoreboardTags().contains("completed")) {
                    cir.setReturnValue(this);
                    cir.cancel();
                    return;
                }

                ChangeDimensionSplitEvent event = (ChangeDimensionSplitEvent) PeepoPractice.CATEGORY.getSplitEvent();
                if (InGameTimer.getInstance().getStatus() != TimerStatus.NONE && event.hasDimension() && event.getDimension() == destination.getRegistryKey()) {
                    event.complete(!this.isDead());
                    cir.setReturnValue(this);
                    cir.cancel();
                }
            }
        }
    }

    @Redirect(method = "changeDimension", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/PortalForcer;usePortal(Lnet/minecraft/entity/Entity;F)Z"))
    private boolean ignorePortal(PortalForcer instance, Entity entity, float yawOffset) {
        if (!PeepoPractice.CATEGORY.equals(PracticeCategories.EMPTY)) {
            return true;
        }
        return instance.usePortal(entity, yawOffset);
    }

    @Override
    public void onDeath(DamageSource source) {
        if (PeepoPractice.CATEGORY.hasSplitEvent()) {
            boolean end = true;
            if (this.getServer() != null && this.getServer().getOverworld() != null && this.getSpawnPointPosition() != null) {
                if (this.getSpawnPointPosition() != this.getServer().getOverworld().getSpawnPos()) {
                    if (this.getSpawnPointPosition().isWithinDistance(this.getPos(), 4.0D)) {
                        end = false;
                    }
                }
            }
            if (end) {
                this.setGameMode(GameMode.SPECTATOR);
                PeepoPractice.CATEGORY.getSplitEvent().complete(false);
            }
        }
        super.onDeath(source);
    }
}