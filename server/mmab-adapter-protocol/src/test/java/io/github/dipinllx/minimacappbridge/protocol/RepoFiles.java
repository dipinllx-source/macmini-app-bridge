package io.github.dipinllx.minimacappbridge.protocol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** 测试读取仓库中的语言无关契约文件（sdk/protocol/...）。 */
public final class RepoFiles {

    private RepoFiles() {
    }

    public static Path root() {
        return Path.of(System.getProperty("mmab.repo.root", "../..")).toAbsolutePath().normalize();
    }

    public static String read(String relative) {
        try {
            return Files.readString(root().resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Path> list(String relativeDir) {
        try (Stream<Path> s = Files.list(root().resolve(relativeDir))) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
