package io.github.dipinllx.minimacappbridge.e2e;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * 测试用自签证书：每次运行用 JDK keytool 现场生成到 target/（不进 Git、不复用）。
 * SAN = IP:127.0.0.1，客户端只信任这张证书，主机名校验保持开启。
 */
final class TestTls {

    final Path keystore;
    final String storePassword;
    final SSLContext clientContext;

    private TestTls(Path keystore, String storePassword, SSLContext clientContext) {
        this.keystore = keystore;
        this.storePassword = storePassword;
        this.clientContext = clientContext;
    }

    static TestTls create() throws Exception {
        Path dir = Path.of("target", "test-tls").toAbsolutePath();
        Files.createDirectories(dir);
        Path ks = dir.resolve("mmab-test-" + UUID.randomUUID() + ".p12");
        String pw = UUID.randomUUID().toString();
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process p = new ProcessBuilder(List.of(keytool.toString(), "-genkeypair", "-alias", "mmab",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", "CN=mmab-offline-test", "-ext", "san=ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", pw))
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("keytool failed");
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, pw.toCharArray());
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("mmab", store.getCertificate("mmab"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return new TestTls(ks, pw, ctx);
    }
}
