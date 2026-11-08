package io.github.dipinllx.macminiappbridge.protocol.codec;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * mmab-sv1 结构化值的受限模型。Java 侧只用于校验与往返测试，
 * 不把 payload 映射为任意 Java 业务类（设计 §9.4）。
 */
public sealed interface SvValue {

    record SvNull() implements SvValue {
    }

    record SvUndefined() implements SvValue {
    }

    record SvBool(boolean value) implements SvValue {
    }

    /** JS number 语义（IEEE-754 double）；record 相等性按 {@link Double#compare}，NaN==NaN、-0≠0。 */
    record SvNumber(double value) implements SvValue {
    }

    record SvString(String value) implements SvValue {
        public SvString {
            Objects.requireNonNull(value, "value");
        }
    }

    record SvBigInt(BigInteger value) implements SvValue {
        public SvBigInt {
            Objects.requireNonNull(value, "value");
        }
    }

    /** {@code epochMillis == null} 表示 Invalid Date。 */
    record SvDate(Double epochMillis) implements SvValue {
    }

    /** ArrayBuffer / TypedArray 的底层字节（小端，与 JS 平台一致）。 */
    record SvBytes(BytesKind kind, byte[] data) implements SvValue {
        public SvBytes {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(data, "data");
            if (data.length % kind.elementSize() != 0) {
                throw new IllegalArgumentException("byte length not a multiple of element size");
            }
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SvBytes b && b.kind == kind && Arrays.equals(b.data, data);
        }

        @Override
        public int hashCode() {
            return 31 * kind.hashCode() + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return "SvBytes[" + kind + ", " + data.length + " bytes]";
        }
    }

    record SvArray(List<SvValue> items) implements SvValue {
        public SvArray {
            items = List.copyOf(items);
        }
    }

    /** 普通对象；键顺序保留，相等性不看顺序。 */
    record SvObject(Map<String, SvValue> fields) implements SvValue {
        public SvObject {
            fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
        }
    }

    record SvMap(List<Entry> entries) implements SvValue {
        public SvMap {
            entries = List.copyOf(entries);
        }
    }

    record Entry(SvValue key, SvValue value) {
        public Entry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    record SvSet(List<SvValue> items) implements SvValue {
        public SvSet {
            items = List.copyOf(items);
        }
    }

    /** 只保留 name 与 message；不传 stack（可能含本机路径）。 */
    record SvError(String name, String message) implements SvValue {
        public SvError {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(message, "message");
        }
    }

    /** MessagePort 引用；端口生命周期见 {@code PortTable}。 */
    record SvPort(String id) implements SvValue {
        public SvPort {
            Objects.requireNonNull(id, "id");
        }
    }
}
