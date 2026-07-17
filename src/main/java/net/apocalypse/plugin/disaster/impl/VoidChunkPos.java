package net.apocalypse.plugin.disaster.impl;

import java.util.UUID;

/** 월드 UUID + 청크 좌표로, 차원 소멸로 영구히 지워진 청크 하나를 식별한다. */
public record VoidChunkPos(UUID world, int x, int z) {

    public String serialize() {
        return world + "|" + x + "|" + z;
    }

    public static VoidChunkPos deserialize(String raw) {
        String[] parts = raw.split("\\|");
        return new VoidChunkPos(UUID.fromString(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }
}
