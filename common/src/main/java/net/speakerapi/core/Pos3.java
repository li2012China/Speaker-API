package net.speakerapi.core;

/** 世界坐标点（等价于 {@code net.minecraft.core.BlockPos} / {@code Vec3} 的 core 侧替身）。 */
public record Pos3(double x, double y, double z) {

    public double distanceTo(Pos3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
