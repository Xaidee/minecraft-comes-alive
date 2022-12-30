package net.mca.server.world.data;

import net.mca.Config;
import net.mca.resources.API;
import net.mca.resources.data.BuildingType;
import net.mca.util.NbtHelper;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Stream;

public class Building implements Serializable, Iterable<UUID> {
    @Serial
    private static final long serialVersionUID = -1106627083469687307L;
    public static final long SCAN_COOLDOWN = 4800;
    private static final Direction[] directions = {
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private final Map<UUID, String> residents = new HashMap<>();
    private final Map<Identifier, List<BlockPos>> blocks = new HashMap<>();

    private String type = "building";
    private String forcedType = null;

    private int size;
    private int pos0X, pos0Y, pos0Z;
    private int pos1X, pos1Y, pos1Z;
    private int posX, posY, posZ;
    private int id;
    private boolean strictScan;
    private long lastScan;

    public Building() {
    }

    public Building(BlockPos pos) {
        this(pos, false);
    }

    public Building(BlockPos pos, boolean strictScan) {
        this();

        pos0X = pos.getX();
        pos0Y = pos.getY();
        pos0Z = pos.getZ();

        pos1X = pos0X;
        pos1Y = pos0Y;
        pos1Z = pos0Z;

        posX = pos0X;
        posY = pos0Y;
        posZ = pos0Z;

        this.strictScan = strictScan;
    }

    public Building(NbtCompound v) {
        id = v.getInt("id");
        size = v.getInt("size");
        pos0X = v.getInt("pos0X");
        pos0Y = v.getInt("pos0Y");
        pos0Z = v.getInt("pos0Z");
        pos1X = v.getInt("pos1X");
        pos1Y = v.getInt("pos1Y");
        pos1Z = v.getInt("pos1Z");
        if (v.contains("posX")) {
            posX = v.getInt("posX");
            posY = v.getInt("posY");
            posZ = v.getInt("posZ");
        } else {
            BlockPos center = getCenter();
            posX = center.getX();
            posY = center.getY();
            posZ = center.getZ();
        }
        forcedType = v.contains("forced_type") ? v.getString("forced_type") : null;
        type = forcedType != null ? forcedType : v.getString("type");

        strictScan = v.getBoolean("strictScan");

        NbtList res = v.getList("residents", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < res.size(); i++) {
            NbtCompound c = res.getCompound(i);
            residents.put(c.getUuid("uuid"), c.getString("name"));
        }

        blocks.putAll(NbtHelper.toMap(v.getCompound("blocks2"),
                Identifier::new,
                l -> NbtHelper.toList(l, e -> {
                    NbtCompound c = (NbtCompound)e;
                    return new BlockPos(c.getInt("x"), c.getInt("y"), c.getInt("z"));
                })));
    }

    public NbtCompound save() {
        NbtCompound v = new NbtCompound();
        v.putInt("id", id);
        v.putInt("size", size);
        v.putInt("pos0X", pos0X);
        v.putInt("pos0Y", pos0Y);
        v.putInt("pos0Z", pos0Z);
        v.putInt("pos1X", pos1X);
        v.putInt("pos1Y", pos1Y);
        v.putInt("pos1Z", pos1Z);
        v.putInt("posX", posX);
        v.putInt("posY", posY);
        v.putInt("posZ", posZ);
        if (forcedType != null) {
            v.putString("forced_type", forcedType);
        }
        v.putString("type", type);
        v.putBoolean("strictScan", strictScan);

        v.put("residents", NbtHelper.fromList(residents.entrySet(), resident -> {
            NbtCompound entry = new NbtCompound();
            entry.putUuid("uuid", resident.getKey());
            entry.putString("name", resident.getValue());
            return entry;
        }));

        NbtCompound b = new NbtCompound();
        NbtHelper.fromMap(
                b,
                blocks,
                Identifier::toString,
                e -> NbtHelper.fromList(e, p -> {
                    NbtCompound entry = new NbtCompound();
                    entry.putInt("x", p.getX());
                    entry.putInt("y", p.getY());
                    entry.putInt("z", p.getZ());
                    return entry;
                })
        );
        v.put("blocks2", b);

        return v;
    }

    public boolean hasFreeSpace() {
        return getBedCount() > getResidents().size();
    }

    public boolean isCrowded() {
        return getBedCount() < getResidents().size();
    }

    public Stream<BlockPos> findEmptyBed(ServerWorld world) {
        return world.getPointOfInterestStorage().getInSquare(
                        registryEntry -> registryEntry.matchesKey(PointOfInterestTypes.HOME),
                        getCenter(),
                        getPos0().getManhattanDistance(getPos1()),
                        PointOfInterestStorage.OccupationStatus.ANY)
                .filter(p -> {
                    BlockState blockState = world.getBlockState(p.getPos());
                    return blockState.isIn(BlockTags.BEDS) && !(Boolean)blockState.get(BedBlock.OCCUPIED);
                })
                .map(PointOfInterest::getPos)
                .filter(this::containsPos);
    }

    public Optional<BlockPos> findClosestEmptyBed(ServerWorld world, BlockPos pos) {
        return findEmptyBed(world).min(Comparator.comparingInt(a -> a.getManhattanDistance(pos)));
    }

    public void addResident(Entity e) {
        if (!residents.containsKey(e.getUuid())) {
            residents.put(e.getUuid(), e.getName().getString());
        }
    }

    public void updateResident(Entity e) {
        if (residents.containsKey(e.getUuid())) {
            residents.put(e.getUuid(), e.getName().getString());
        }
    }

    public BlockPos getPos0() {
        return new BlockPos(pos0X, pos0Y, pos0Z);
    }

    public BlockPos getPos1() {
        return new BlockPos(pos1X, pos1Y, pos1Z);
    }

    public BlockPos getCenter() {
        return new BlockPos(
                (pos0X + pos1X) / 2,
                (pos0Y + pos1Y) / 2,
                (pos0Z + pos1Z) / 2
        );
    }

    public BlockPos getSourceBlock() {
        return new BlockPos(posX, posY, posZ);
    }

    public void validateBlocks(World world) {
        setLastScan(world.getTime());

        //remove all invalid blocks
        for (Map.Entry<Identifier, List<BlockPos>> positions : blocks.entrySet()) {
            List<BlockPos> mask = positions.getValue().stream()
                    .filter(p -> !Registries.BLOCK.getId(world.getBlockState(p).getBlock()).equals(positions.getKey()))
                    .toList();
            positions.getValue().removeAll(mask);
        }
    }

    public Stream<BlockPos> getBlockPosStream() {
        return blocks.values().stream().flatMap(Collection::stream);
    }

    public void addPOI(World world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        removeBlock(block, pos);
        addBlock(block, pos);

        //validate grouped buildings
        validateBlocks(world);

        //mean center
        int n = (int)getBlockPosStream().count();
        if (n > 0) {
            BlockPos center = getBlockPosStream().reduce(BlockPos.ORIGIN, BlockPos::add);
            pos0X = center.getX() / n;
            pos0Y = center.getY() / n;
            pos0Z = center.getZ() / n;
            pos1X = pos0X;
            pos1Y = pos0Y;
            pos1Z = pos0Z;
        }
    }

    public enum validationResult {
        OVERLAP,
        BLOCK_LIMIT,
        SIZE_LIMIT,
        NO_DOOR,
        TOO_SMALL,
        IDENTICAL,
        SUCCESS,
        INVALID_TYPE
    }

    public validationResult validateBuilding(World world, Set<BlockPos> blocked) {
        //clear old building
        blocks.clear();
        size = 0;

        setLastScan(world.getTime());

        //temp data for flood fill
        Set<BlockPos> done = new HashSet<>();
        LinkedList<BlockPos> queue = new LinkedList<>();

        //start point
        BlockPos center = getSourceBlock();
        queue.add(center);
        done.add(center);

        //const
        final int minSize = Config.getInstance().minBuildingSize;
        final int maxSize = Config.getInstance().maxBuildingSize;
        final int maxRadius = Config.getInstance().maxBuildingRadius;

        //fill the building
        int scanSize = 0;
        int interiorSize = 0;
        boolean hasDoor = false;
        Map<BlockPos, Boolean> roofCache = new HashMap<>();
        while (!queue.isEmpty() && scanSize < maxSize) {
            BlockPos p = queue.removeLast();

            //this block is marked as blocked, indicating an overlap
            if (blocked.contains(p) && scanSize > 0) {
                return validationResult.OVERLAP;
            }

            //as long the max radius is not reached
            if (p.getManhattanDistance(center) < maxRadius) {
                for (Direction d : directions) {
                    BlockPos n = p.offset(d);

                    //and the block is not already checked
                    if (!done.contains(n)) {
                        BlockState state = world.getBlockState(n);

                        //mark it
                        done.add(n);

                        //if not solid, continue
                        if (state.isAir()) {
                            if (!roofCache.containsKey(n)) {
                                BlockPos n2 = n;
                                int maxScanHeight = 16;
                                for (int i = 0; i < maxScanHeight; i++) {
                                    roofCache.put(n2, false);
                                    n2 = n2.up();

                                    //found valid block
                                    BlockState block = world.getBlockState(n2);
                                    if (!block.isAir() || roofCache.containsKey(n2)) {
                                        if (!(roofCache.containsKey(n2) && !roofCache.get(n2)) && !block.isIn(BlockTags.LEAVES)) {
                                            for (int i2 = i; i2 >= 0; i2--) {
                                                n2 = n2.down();
                                                roofCache.put(n2, true);
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                            if (roofCache.get(n)) {
                                interiorSize++;
                                queue.add(n);
                            }
                        } else if (state.getBlock() instanceof DoorBlock) {
                            //skip door and start a new room
                            if (!strictScan) {
                                queue.add(n);
                            }
                            hasDoor = true;
                        }
                    }
                }
            } else {
                return validationResult.SIZE_LIMIT;
            }

            scanSize++;
        }

        // min size is 32 by default, which equals an 8 block big cube with 6 times 4 sides
        if (!queue.isEmpty()) {
            return validationResult.BLOCK_LIMIT;
        } else if (done.size() <= minSize) {
            return validationResult.TOO_SMALL;
        } else if (!hasDoor) {
            return validationResult.NO_DOOR;
        } else {
            //fetch all interesting block types
            Set<Identifier> blockTypes = new HashSet<>();
            for (BuildingType bt : API.getVillagePool()) {
                blockTypes.addAll(bt.getBlockToGroup().keySet());
            }

            //dimensions
            int sx = center.getX();
            int sy = center.getY();
            int sz = center.getZ();
            int ex = sx;
            int ey = sy;
            int ez = sz;

            for (BlockPos p : done) {
                sx = Math.min(sx, p.getX());
                sy = Math.min(sy, p.getY());
                sz = Math.min(sz, p.getZ());
                ex = Math.max(ex, p.getX());
                ey = Math.max(ey, p.getY());
                ez = Math.max(ez, p.getZ());

                //count blocks types
                BlockState blockState = world.getBlockState(p);
                Block block = blockState.getBlock();
                if (blockTypes.contains(Registries.BLOCK.getId(block))) {
                    if (block instanceof BedBlock) {
                        // TODO: look for better solution for 7.4.0
                        if (blockState.get(BedBlock.PART) == BedPart.HEAD) {
                            addBlock(block, p);
                        }
                    } else {
                        addBlock(block, p);
                    }
                }
            }

            //adjust building dimensions
            pos0X = sx;
            pos0Y = sy;
            pos0Z = sz;

            pos1X = ex;
            pos1Y = ey;
            pos1Z = ez;

            size = interiorSize;

            //determine type
            boolean assignedType = false;
            if (!type.equals("blocked")) {
                assignedType = determineType();
            }

            return assignedType ? validationResult.SUCCESS : validationResult.INVALID_TYPE;
        }
    }

    public boolean determineType() {
        int bestPriority = -1;
        boolean assignedType = false;

        for (BuildingType bt : API.getVillagePool()) {
            final boolean checkingForcedType = forcedType != null && forcedType.equalsIgnoreCase(bt.name());
            if ((checkingForcedType || bt.priority() > bestPriority) && size >= bt.size()) {
                //get an overview of the satisfied blocks
                Map<Identifier, List<BlockPos>> available = bt.getGroups(blocks);
                boolean valid = bt.getGroups().entrySet().stream().noneMatch(e -> !available.containsKey(e.getKey()) || available.get(e.getKey()).size() < e.getValue());
                if (valid) {
                    bestPriority = bt.priority();
                    type = bt.name();
                    assignedType = true;

                    if (checkingForcedType) {
                        break;
                    }
                } else if (checkingForcedType) {
                    assignedType = false;
                }
            }
        }
        return assignedType;
    }

    public String getType() {
        return type;
    }

    public String getForcedType() {
        return forcedType;
    }

    public BuildingType getBuildingType() {
        return API.getVillagePool().getBuildingType(type);
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setForcedType(String type) { this.forcedType = type; }

    public Map<UUID, String> getResidents() {
        return residents;
    }

    @Override
    public Iterator<UUID> iterator() {
        return residents.keySet().iterator();
    }

    public boolean hasResident(UUID id) {
        return residents.containsKey(id);
    }

    public Map<Identifier, List<BlockPos>> getBlocks() {
        return blocks;
    }

    public void addBlock(Block block, BlockPos p) {
        Identifier key = Registries.BLOCK.getId(block);
        if (!blocks.containsKey(key)) {
            blocks.put(key, new ArrayList<>());
        }
        blocks.get(key).add(p);
    }

    public void removeBlock(Block block, BlockPos p) {
        Identifier key = Registries.BLOCK.getId(block);
        if (blocks.containsKey(key)) {
            blocks.get(key).remove(p);
        }
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public boolean overlaps(Building b) {
        return pos1X > b.pos0X && pos0X < b.pos1X && pos1Y > b.pos0Y && pos0Y < b.pos1Y && pos1Z > b.pos0Z && pos0Z < b.pos1Z;
    }

    public boolean containsPos(Vec3i pos) {
        return pos.getX() >= pos0X && pos.getX() <= pos1X
                && pos.getY() >= pos0Y && pos.getY() <= pos1Y
                && pos.getZ() >= pos0Z && pos.getZ() <= pos1Z;
    }

    public boolean isIdentical(Building b) {
        return pos0X == b.pos0X && pos1X == b.pos1X && pos0Y == b.pos0Y && pos1Y == b.pos1Y && pos0Z == b.pos0Z && pos1Z == b.pos1Z;
    }

    public int getBedCount() {
        return getBlocksOfGroup(new Identifier("minecraft:beds")).size();
    }

    @Deprecated
    public List<BlockPos> getBlocksOfGroup(Identifier i) {
        Map<Identifier, List<BlockPos>> groups = API.getVillagePool().getBuildingType("?").getGroups(blocks);
        return groups.getOrDefault(i, new ArrayList<>());
    }

    public int getSize() {
        return size;
    }

    public long getLastScan() {
        return lastScan;
    }

    public void setLastScan(long lastScan) {
        this.lastScan = lastScan;
    }
}
