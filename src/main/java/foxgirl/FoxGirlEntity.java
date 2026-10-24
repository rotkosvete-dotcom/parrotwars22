package foxgirl;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;

/**
 * Лисичка-компаньон для выживания (живёт в Закулисье).
 *  - Приручается едой (шанс 1/3 за кормление).
 *  - Сытость: пока сыта — помогает, голодная — слабеет и не помогает.
 *  - Защищает хозяина, бьёт монстров (кроме криперов — от них убегает).
 *  - Лечит хозяина (регенерация), если ему плохо.
 *  - Ночью подсвечивает ближайших монстров (Glowing).
 *  - ПКМ пустой рукой — сесть / идти следом.
 */
public class FoxGirlEntity extends TamableAnimal {
	private static final int MAX_HUNGER = 20;
	private static final int HUNGER_TICKS = 1200; // -1 сытости в минуту
	private int hunger = 12;

	public FoxGirlEntity(EntityType<? extends FoxGirlEntity> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 24.0)
			.add(Attributes.MOVEMENT_SPEED, 0.32)
			.add(Attributes.ATTACK_DAMAGE, 4.0)
			.add(Attributes.FOLLOW_RANGE, 24.0);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(1, new FloatGoal(this));
		this.goalSelector.addGoal(2, new SitWhenOrderedToGoal(this));
		this.goalSelector.addGoal(3, new AvoidEntityGoal<>(this, Creeper.class, 8.0f, 1.1, 1.6));
		this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.2, true));
		this.goalSelector.addGoal(5, new FollowOwnerGoal(this, 1.1, 8.0f, 2.0f, false));
		this.goalSelector.addGoal(6, new TemptGoal(this, 1.1,
			Ingredient.of(Items.SWEET_BERRIES, Items.COOKED_CHICKEN, Items.COOKED_BEEF, Items.COOKED_PORKCHOP), false));
		this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.9));
		this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
		this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

		this.targetSelector.addGoal(1, new OwnerHurtByTargetGoal(this));
		this.targetSelector.addGoal(2, new OwnerHurtTargetGoal(this));
		this.targetSelector.addGoal(3, new HurtByTargetGoal(this).setAlertOthers());
		this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Monster.class, 5, false, false,
			e -> this.isTame() && this.hunger > 0 && !(e instanceof Creeper)));
	}

	@Override
	public void setTame(boolean tame) {
		super.setTame(tame);
		if (tame) {
			this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(30.0);
			this.setHealth(30.0f);
		}
	}

	@Override
	public InteractionResult mobInteract(Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);

		if (stack.isEdible()) {
			boolean needsFood = !this.isTame() || this.hunger < MAX_HUNGER || this.getHealth() < this.getMaxHealth();
			if (needsFood) {
				if (!this.level().isClientSide) {
					FoodProperties food = stack.getItem().getFoodProperties();
					int value = food != null ? food.getNutrition() : 1;
					if (!player.getAbilities().instabuild) stack.shrink(1);
					this.hunger = Math.min(MAX_HUNGER, this.hunger + value * 2);
					this.heal(value);
					if (!this.isTame()) {
						if (this.random.nextInt(3) == 0) {
							this.tame(player);
							this.navigation.stop();
							this.setTarget(null);
							this.setOrderedToSit(false);
							this.setInSittingPose(false);
							this.level().broadcastEntityEvent(this, (byte) 7); // сердечки
							player.displayClientMessage(Component.translatable("message.foxgirl.tamed"), true);
						} else {
							this.level().broadcastEntityEvent(this, (byte) 6); // дымок
						}
					} else {
						this.level().broadcastEntityEvent(this, (byte) 7);
					}
				}
				return InteractionResult.sidedSuccess(this.level().isClientSide);
			}
		}

		if (this.isTame() && this.isOwnedBy(player) && hand == InteractionHand.MAIN_HAND && stack.isEmpty()) {
			if (!this.level().isClientSide) {
				boolean sit = !this.isOrderedToSit();
				this.setOrderedToSit(sit);
				this.setInSittingPose(sit);
				this.jumping = false;
				this.navigation.stop();
				this.setTarget(null);
				player.displayClientMessage(Component.translatable(sit ? "message.foxgirl.sit" : "message.foxgirl.follow"), true);
			}
			return InteractionResult.sidedSuccess(this.level().isClientSide);
		}
		return InteractionResult.PASS;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide || !this.isTame()) return;

		// Голод
		if (this.tickCount % HUNGER_TICKS == 0 && this.hunger > 0) {
			this.hunger--;
			if (this.hunger == 0 && this.getOwner() instanceof ServerPlayer sp) {
				sp.displayClientMessage(Component.translatable("message.foxgirl.hungry"), true);
			}
		}
		if (this.hunger == 0) {
			if (this.tickCount % 100 == 0) {
				this.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 140, 0, true, false));
			}
			return;
		}

		// Помощь хозяину
		if (this.tickCount % 100 == 0) {
			LivingEntity owner = this.getOwner();
			if (owner == null || this.distanceToSqr(owner) > 12 * 12) return;

			if (owner.getHealth() <= owner.getMaxHealth() / 2.0f) {
				owner.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, true, true));
			}
			if (this.level().isNight()) {
				for (Monster m : this.level().getEntitiesOfClass(Monster.class, this.getBoundingBox().inflate(16.0), e -> true)) {
					m.addEffect(new MobEffectInstance(MobEffects.GLOWING, 120, 0, true, false));
				}
			}
		}
	}

	@Override
	public boolean wantsToAttack(LivingEntity target, LivingEntity owner) {
		if (target instanceof Creeper || target instanceof Ghast) return false;
		if (target instanceof FoxGirlEntity other) return other.getOwner() != owner;
		if (target instanceof Player p && owner instanceof Player po && !po.canHarmPlayer(p)) return false;
		return !(target instanceof TamableAnimal t && t.isTame());
	}

	@Override
	public boolean isFood(ItemStack stack) { return false; }

	@Override
	public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob other) { return null; }

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putInt("FoxHunger", this.hunger);
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		if (tag.contains("FoxHunger")) this.hunger = tag.getInt("FoxHunger");
	}

	@Override protected SoundEvent getAmbientSound() { return SoundEvents.FOX_AMBIENT; }
	@Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.FOX_HURT; }
	@Override protected SoundEvent getDeathSound() { return SoundEvents.FOX_DEATH; }
}
