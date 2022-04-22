package io.github.apace100.apoli.mixin;

import io.github.apace100.apoli.access.ModifiableFoodEntity;
import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.networking.ModPackets;
import io.github.apace100.apoli.power.*;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Nameable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin extends LivingEntity implements Nameable, CommandOutput {

    @Shadow
    public abstract boolean damage(DamageSource source, float amount);

    @Shadow
    public abstract EntityDimensions getDimensions(EntityPose pose);

    @Shadow
    public abstract ItemStack getEquippedStack(EquipmentSlot slot);

    @Shadow
    protected boolean isSubmergedInWater;

    @Shadow
    @Final
    public PlayerInventory inventory;

    @Shadow
    public abstract ItemEntity dropItem(ItemStack stack, boolean retainOwnership);

    @Shadow public abstract ActionResult interact(Entity entity, Hand hand);

    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }

    @ModifyVariable(method = "eatFood", at = @At("HEAD"), argsOnly = true)
    private ItemStack modifyEatenItemStack(ItemStack original) {
        List<ModifyFoodPower> mfps = PowerHolderComponent.getPowers(this, ModifyFoodPower.class);
        mfps = mfps.stream().filter(mfp -> mfp.doesApply(original)).collect(Collectors.toList());
        ItemStack newStack = original;
        for(ModifyFoodPower mfp : mfps) {
            newStack = mfp.getConsumedItemStack(newStack);
        }
        ((ModifiableFoodEntity)this).setCurrentModifyFoodPowers(mfps);
        ((ModifiableFoodEntity)this).setOriginalFoodStack(original);
        return newStack;
    }

    @Unique
    private ActionResult apoli$CachedPriorityZeroResult;

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void preventEntityInteraction(Entity entity, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if(this.isSpectator()) {
            return;
        }
        ItemStack stack = this.getStackInHand(hand);
        for(PreventEntityUsePower peup : PowerHolderComponent.getPowers(this, PreventEntityUsePower.class)) {
            if(peup.doesApply(entity, hand, stack)) {
                cir.setReturnValue(peup.executeAction(entity, hand));
                cir.cancel();
                return;
            }
        }
        for(PreventBeingUsedPower pbup : PowerHolderComponent.getPowers(entity, PreventBeingUsedPower.class)) {
            if(pbup.doesApply((PlayerEntity) (Object) this, hand, stack)) {
                cir.setReturnValue(pbup.executeAction((PlayerEntity) (Object) this, hand));
                cir.cancel();
                return;
            }
        }
        apoli$CachedPriorityZeroResult = ActionResult.PASS;
        HashMap<Integer, List<InteractionPower>> interactionPowers = new HashMap<>();
        int max = 0;
        for(ActionOnEntityUsePower p :
            PowerHolderComponent.getPowers(this, ActionOnEntityUsePower.class).stream().filter(p -> p.shouldExecute(entity, hand, stack) && p.getPriority() >= 0).toList()) {
            int prio = p.getPriority();
            if(interactionPowers.containsKey(prio)) {
                interactionPowers.get(prio).add(p);
            } else {
                List<InteractionPower> powerList = new LinkedList<>();
                powerList.add(p);
                interactionPowers.put(prio, powerList);
            }
            if(prio > max) {
                max = prio;
            }
        }
        for(ActionOnBeingUsedPower p :
            PowerHolderComponent.getPowers(this, ActionOnBeingUsedPower.class).stream().filter(p -> p.shouldExecute((PlayerEntity) (Object) this, hand, stack) && p.getPriority() >= 0).toList()) {
            int prio = p.getPriority();
            if(interactionPowers.containsKey(prio)) {
                interactionPowers.get(prio).add(p);
            } else {
                List<InteractionPower> powerList = new LinkedList<>();
                powerList.add(p);
                interactionPowers.put(prio, powerList);
            }
            if(prio > max) {
                max = prio;
            }
        }
        for(int i = max; i >= 0; i--) {
            if(!interactionPowers.containsKey(i)) {
                continue;
            }
            List<InteractionPower> powers = interactionPowers.get(i);
            ActionResult result = ActionResult.PASS;
            for(InteractionPower ip : powers) {
                ActionResult ar = ActionResult.PASS;
                if(ip instanceof ActionOnEntityUsePower aoeup) {
                    ar = aoeup.executeAction(entity, hand);
                } else if(ip instanceof ActionOnBeingUsedPower aobup) {
                    ar = aobup.executeAction((PlayerEntity) (Object) this, hand);
                }
                if(ar.isAccepted() && !result.isAccepted()) {
                    result = ar;
                } else if(ar.shouldSwingHand() && !result.shouldSwingHand()) {
                    result = ar;
                }
            }
            if(i == 0) {
                apoli$CachedPriorityZeroResult = result;
            } else {
                apoli$CachedPriorityZeroResult = ActionResult.PASS;
                if(result != ActionResult.PASS) {
                    if(result.shouldSwingHand()) {
                        this.swingHand(hand);
                    }
                    cir.setReturnValue(result);
                    break;
                }
            }
        }
    }

    @Inject(method = "interact", at = @At("RETURN"), cancellable = true)
    private void entityInteractionAfter(Entity entity, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        ActionResult original = cir.getReturnValue();
        ActionResult custom = ActionResult.PASS;
        if(apoli$CachedPriorityZeroResult != ActionResult.PASS) {
            custom = apoli$CachedPriorityZeroResult;
        } else if(cir.getReturnValue() == ActionResult.PASS) {
            ItemStack stack = this.getStackInHand(hand);
            HashMap<Integer, List<InteractionPower>> interactionPowers = new HashMap<>();
            int min = 0;
            for(ActionOnEntityUsePower p :
                PowerHolderComponent.getPowers(this, ActionOnEntityUsePower.class).stream().filter(p -> p.shouldExecute(entity, hand, stack) && p.getPriority() < 0).toList()) {
                int prio = p.getPriority();
                if(interactionPowers.containsKey(prio)) {
                    interactionPowers.get(prio).add(p);
                } else {
                    List<InteractionPower> powerList = new LinkedList<>();
                    powerList.add(p);
                    interactionPowers.put(prio, powerList);
                }
                if(prio < min) {
                    min = prio;
                }
            }
            for(ActionOnBeingUsedPower p :
                PowerHolderComponent.getPowers(this, ActionOnBeingUsedPower.class).stream().filter(p -> p.shouldExecute((PlayerEntity) (Object) this, hand, stack) && p.getPriority() < 0).toList()) {
                int prio = p.getPriority();
                if(interactionPowers.containsKey(prio)) {
                    interactionPowers.get(prio).add(p);
                } else {
                    List<InteractionPower> powerList = new LinkedList<>();
                    powerList.add(p);
                    interactionPowers.put(prio, powerList);
                }
                if(prio < min) {
                    min = prio;
                }
            }
            for(int i = -1; i >= min; i--) {
                if(!interactionPowers.containsKey(i)) {
                    continue;
                }
                List<InteractionPower> powers = interactionPowers.get(i);
                ActionResult result = ActionResult.PASS;
                for(InteractionPower ip : powers) {
                    ActionResult ar = ActionResult.PASS;
                    if(ip instanceof ActionOnEntityUsePower aoeup) {
                        ar = aoeup.executeAction(entity, hand);
                    } else if(ip instanceof ActionOnBeingUsedPower aobup) {
                        ar = aobup.executeAction((PlayerEntity) (Object) this, hand);
                    }
                    if(ar.isAccepted() && !result.isAccepted()) {
                        result = ar;
                    } else if(ar.shouldSwingHand() && !result.shouldSwingHand()) {
                        result = ar;
                    }
                }
                if(i == 0) {
                    apoli$CachedPriorityZeroResult = result;
                } else {
                    apoli$CachedPriorityZeroResult = ActionResult.PASS;
                    if(result != ActionResult.PASS) {
                        custom = result;
                        break;
                    }
                }
            }
        }
        if(custom.shouldSwingHand()) {
            this.swingHand(hand);
        }
        if(original.isAccepted() && !custom.isAccepted()) {
        } else if(original.shouldSwingHand() && !custom.shouldSwingHand()) {
        } else {
            cir.setReturnValue(custom);
        }
    }

    @Inject(method = "dismountVehicle", at = @At("HEAD"))
    private void sendPlayerDismountPacket(CallbackInfo ci) {
        if(!world.isClient && getVehicle() instanceof PlayerEntity) {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            buf.writeInt(getId());
            ServerPlayNetworking.send((ServerPlayerEntity) getVehicle(), ModPackets.PLAYER_DISMOUNT, buf);
        }
    }

    @Inject(method = "updateSwimming", at = @At("TAIL"))
    private void updateSwimmingPower(CallbackInfo ci) {
        if(PowerHolderComponent.hasPower(this, SwimmingPower.class)) {
            this.setSwimming(this.isSprinting() && !this.hasVehicle());
            this.touchingWater = this.isSwimming();
            if (this.isSwimming()) {
                this.fallDistance = 0.0F;
                Vec3d look = this.getRotationVector();
                move(MovementType.SELF, new Vec3d(look.x/4, look.y/4, look.z/4));
            }
        } else if(PowerHolderComponent.hasPower(this, IgnoreWaterPower.class)) {
            this.setSwimming(false);
        }
    }

    @Inject(method = "wakeUp(ZZ)V", at = @At("HEAD"))
    private void invokeWakeUpAction(boolean bl, boolean updateSleepingPlayers, CallbackInfo ci) {
        if(!bl && !updateSleepingPlayers && getSleepingPosition().isPresent()) {
            BlockPos sleepingPos = getSleepingPosition().get();
            PowerHolderComponent.getPowers(this, ActionOnWakeUp.class).stream().filter(p -> p.doesApply(sleepingPos)).forEach(p -> p.executeActions(sleepingPos, Direction.DOWN));
        }
    }

    // Prevent healing if DisableRegenPower
    // Note that this function was called "shouldHeal" instead of "canFoodHeal" at some point in time.
    @Inject(method = "canFoodHeal", at = @At("HEAD"), cancellable = true)
    private void disableHeal(CallbackInfoReturnable<Boolean> info) {
        if(PowerHolderComponent.hasPower(this, DisableRegenPower.class)) {
            info.setReturnValue(false);
        }
    }

    // ModifyExhaustion
    @ModifyVariable(at = @At("HEAD"), method = "addExhaustion", ordinal = 0, name = "exhaustion")
    private float modifyExhaustion(float exhaustionIn) {
        return PowerHolderComponent.modify(this, ModifyExhaustionPower.class, exhaustionIn);
    }

    @Inject(method = "dropInventory", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerInventory;dropAll()V"))
    private void dropAdditionalInventory(CallbackInfo ci) {
        PowerHolderComponent.getPowers(this, InventoryPower.class).forEach(inventory -> {
            if(inventory.shouldDropOnDeath()) {
                for(int i = 0; i < inventory.size(); ++i) {
                    ItemStack itemStack = inventory.getStack(i);
                    if(inventory.shouldDropOnDeath(itemStack)) {
                        if (!itemStack.isEmpty() && EnchantmentHelper.hasVanishingCurse(itemStack)) {
                            inventory.removeStack(i);
                        } else {
                            ((PlayerEntity)(Object)this).dropItem(itemStack, true, false);
                            inventory.setStack(i, ItemStack.EMPTY);
                        }
                    }
                }
            }
        });
        PowerHolderComponent.getPowers(this, KeepInventoryPower.class).forEach(keepInventoryPower -> {
            keepInventoryPower.preventItemsFromDropping(inventory);
        });
    }

    @Inject(method = "dropInventory", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerInventory;dropAll()V", shift = At.Shift.AFTER))
    private void restoreKeptInventory(CallbackInfo ci) {
        PowerHolderComponent.getPowers(this, KeepInventoryPower.class).forEach(keepInventoryPower -> {
            keepInventoryPower.restoreSavedItems(inventory);
        });
    }

    @Inject(method = "canEquip", at = @At("HEAD"), cancellable = true)
    private void preventArmorDispensing(ItemStack stack, CallbackInfoReturnable<Boolean> info) {
        EquipmentSlot slot = MobEntity.getPreferredEquipmentSlot(stack);
        PowerHolderComponent component = PowerHolderComponent.KEY.get(this);
        if(component.getPowers(RestrictArmorPower.class).stream().anyMatch(rap -> !rap.canEquip(stack, slot))) {
            info.setReturnValue(false);
        }
    }
}
