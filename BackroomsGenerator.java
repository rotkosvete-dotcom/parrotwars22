package foxgirl;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Бесконечные «Закулисья»: сетка комнат 6x6, стены с проёмами, потолочные лампы.
 * Блоки меняются в константах ниже.
 */
public class BackroomsGenerator extends ChunkGenerator {
	public static final Codec<BackroomsGenerator> CODEC = RecordCodecBuilder.create(inst -> inst.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource)
	).apply(inst, inst.stable(BackroomsGenerator::new)));

	private static final BlockState FLOOR = Blocks.YELLOW_TERRACOTTA.defaultBlockState();
	private static final BlockState WALL = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
	private static final BlockState CEILING = Blocks.SMOOTH_QUARTZ.defaultBlockState();
	private static final BlockState LIGHT = Blocks.SEA_LANTERN.defaultBlockState();
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private static final int CELL = 6;
	private static final int FLOOR_Y = 0;
	private static final int CEILING_Y = 5; // коридоры высотой 4 блока (y = 1..4)

	public BackroomsGenerator(BiomeSource biomeSource) {
		super(biomeSource);
	}

	@Override
	protected Codec<? extends ChunkGenerator> codec() { return CODEC; }

	// ---- генерация ----

	private static long mix(long x) {
		x += 0x9E3779B97F4A7C15L;
		x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
		x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
		return x ^ (x >>> 31);
	}

	private static double rnd(int a, int b, int salt) {
		long h = mix(mix(a * 341873128712L ^ b * 132897987541L) ^ salt);
		return (h >>> 11) * (1.0 / (1L << 53));
	}

	private static boolean isWall(int wx, int wz) {
		int mx = Math.floorMod(wx, CELL), mz = Math.floorMod(wz, CELL);
		int cx = Math.floorDiv(wx, CELL), cz = Math.floorDiv(wz, CELL);
		if (mx == 0 && mz == 0) return true;               // столбик на углу
		if (mx == 0) {                                      // стена вдоль Z
			double r = rnd(cx, cz, 1);
			if (r < 0.15) return false;                     // совсем открытый пролёт
			return !(r < 0.60 && mz >= 2 && mz <= 4);       // проём 3 блока
		}
		if (mz == 0) {                                      // стена вдоль X
			double r = rnd(cx, cz, 2);
			if (r < 0.15) return false;
			return !(r < 0.60 && mx >= 2 && mx <= 4);
		}
		return false;
	}

	private static boolean isLamp(int wx, int wz) {
		int mx = Math.floorMod(wx, CELL), mz = Math.floorMod(wz, CELL);
		if (mx < 2 || mx > 3 || mz < 2 || mz > 3) return false;
		return rnd(Math.floorDiv(wx, CELL), Math.floorDiv(wz, CELL), 3) < 0.85; // иногда лампа "перегорела"
	}

	@Override
	public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, RandomState random,
														StructureManager structures, ChunkAccess chunk) {
		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();

		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				int wx = minX + x, wz = minZ + z;
				boolean wall = isWall(wx, wz);

				set(chunk, pos.set(x, FLOOR_Y, z), FLOOR, oceanFloor, surface);
				for (int y = FLOOR_Y + 1; y < CEILING_Y; y++) {
					set(chunk, pos.set(x, y, z), wall ? WALL : AIR, oceanFloor, surface);
				}
				set(chunk, pos.set(x, CEILING_Y, z), isLamp(wx, wz) ? LIGHT : CEILING, oceanFloor, surface);
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	private static void set(ChunkAccess chunk, BlockPos.MutableBlockPos pos, BlockState state, Heightmap a, Heightmap b) {
		chunk.setBlockState(pos, state, false);
		a.update(pos.getX(), pos.getY(), pos.getZ(), state);
		b.update(pos.getX(), pos.getY(), pos.getZ(), state);
	}

	// ---- остальные обязательные методы ----

	@Override public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes,
									   StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step) {}
	@Override public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {}
	@Override public void spawnOriginalMobs(WorldGenRegion region) {}
	@Override public int getGenDepth() { return 64; }
	@Override public int getSeaLevel() { return 0; }
	@Override public int getMinY() { return 0; }

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
		return FLOOR_Y + 1;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
		return new NoiseColumn(level.getMinBuildHeight(), new BlockState[]{FLOOR});
	}

	@Override
	public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {}
}
