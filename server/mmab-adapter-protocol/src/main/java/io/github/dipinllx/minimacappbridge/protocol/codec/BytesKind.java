package io.github.dipinllx.minimacappbridge.protocol.codec;

import java.util.Arrays;
import java.util.Optional;

/** mmab-sv1 支持的二进制视图种类（与 JS 构造器名一致）。DataView 等未列出的视图不支持。 */
public enum BytesKind {
    ArrayBuffer(1),
    Int8Array(1),
    Uint8Array(1),
    Uint8ClampedArray(1),
    Int16Array(2),
    Uint16Array(2),
    Int32Array(4),
    Uint32Array(4),
    Float32Array(4),
    Float64Array(8),
    BigInt64Array(8),
    BigUint64Array(8);

    private final int elementSize;

    BytesKind(int elementSize) {
        this.elementSize = elementSize;
    }

    public int elementSize() {
        return elementSize;
    }

    public static Optional<BytesKind> fromName(String name) {
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst();
    }
}
