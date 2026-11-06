package io.github.dipinllx.minimacappbridge.instances;

import io.github.dipinllx.minimacappbridge.protocol.ErrorCode;
import io.github.dipinllx.minimacappbridge.protocol.ProtocolException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 按 {@code \n} 分行、带长度上限的读取器：超长行直接拒绝，不会把对端的无界输出读进内存。 */
final class BoundedLineReader {

    private final InputStream in;
    private final byte[] buf = new byte[64 * 1024];
    private int pos;
    private int len;

    BoundedLineReader(InputStream in) {
        this.in = in;
    }

    /** @return 一行（不含换行符）；EOF 返回 null */
    String readLine(int maxBytes) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            if (pos == len) {
                len = in.read(buf);
                pos = 0;
                if (len <= 0) {
                    len = 0;
                    return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
                }
            }
            int start = pos;
            while (pos < len && buf[pos] != '\n') {
                pos++;
            }
            line.write(buf, start, pos - start);
            if (line.size() > maxBytes) {
                throw new ProtocolException(ErrorCode.QUEUE_LIMIT, "line exceeds " + maxBytes + " bytes");
            }
            if (pos < len) {
                pos++; // skip '\n'
                return line.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
