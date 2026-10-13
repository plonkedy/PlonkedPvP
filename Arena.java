package dev.plonked.pvparena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

public final class Arena {

    private String name;
    private final String worldName;
    private final int minX, minY, minZ, maxX, maxY, maxZ;

    // spawn stored as raw numbers so it survives the world not being loaded yet
    private boolean hasSpawn;
    private double sx, sy, sz;
    private float syaw, spitch;

    public Arena(String name, String worldName, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.name = name;
        this.worldName = worldName;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getWorldName() { return worldName; }
    public World getWorld() { return Bukkit.getWorld(worldName); }

    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    public String sizeString() {
        return (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1);
    }

    public boolean contains(Location loc, boolean fullHeight) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().getName().equals(worldName)) return false;
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
        return fullHeight || (y >= minY && y <= maxY);
    }

    public BoundingBox boundingBox(boolean fullHeight) {
        World w = getWorld();
        double y1 = (fullHeight && w != null) ? w.getMinHeight() : minY;
        double y2 = (fullHeight && w != null) ? w.getMaxHeight() : maxY + 1;
        return new BoundingBox(minX, y1, minZ, maxX + 1, y2, maxZ + 1);
    }

    public void setSpawn(Location loc) {
        if (loc == null) {
            hasSpawn = false;
            return;
        }
        hasSpawn = true;
        sx = loc.getX();
        sy = loc.getY();
        sz = loc.getZ();
        syaw = loc.getYaw();
        spitch = loc.getPitch();
    }

    public void setSpawnRaw(double x, double y, double z, float yaw, float pitch) {
        hasSpawn = true;
        sx = x; sy = y; sz = z; syaw = yaw; spitch = pitch;
    }

    public boolean hasSpawn() { return hasSpawn; }

    public Location getSpawn() {
        if (!hasSpawn) return null;
        World w = getWorld();
        if (w == null) return null;
        return new Location(w, sx, sy, sz, syaw, spitch);
    }

    public double[] spawnRaw() { return new double[]{sx, sy, sz, syaw, spitch}; }
}
