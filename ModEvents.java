package foxgirl;

import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public class ModEvents {
	@Mod.EventBusSubscriber(modid = FoxGirlMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
	public static class ModBus {
		@SubscribeEvent
		public static void onAttributes(EntityAttributeCreationEvent event) {
			event.put(FoxGirlMod.FOX_GIRL.get(), FoxGirlEntity.createAttributes().build());
		}
	}

	@Mod.EventBusSubscriber(modid = FoxGirlMod.MOD_ID)
	public static class ForgeBus {
		/** /backrooms — телепорт туда и обратно (для операторов, удобно для проверки). */
		@SubscribeEvent
		public static void onCommands(RegisterCommandsEvent event) {
			event.getDispatcher().register(Commands.literal("backrooms")
				.requires(src -> src.hasPermission(2))
				.executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					Backrooms.toggle(player);
					return 1;
				}));
		}
	}
}
