package dev.garden.demo.supervisor.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Agent 清单的 HMAC-SHA256 签名与校验。
 *
 * <p>密钥优先读取环境变量 {@code SUPERVISOR_REGISTRY_SECRET}；未配置时生成只在本进程
 * 有效的随机密钥，并在审计中记录 {@code keySource=local-ephemeral}。密钥本身不进入
 * 源码、配置、日志或 HTTP 响应。未配置环境变量时，跨进程重签名由目录服务在启动时完成，
 * 页面仍能看到真实的签名校验结论。
 */
@Service
public final class RegistrySecurityService {
    private static final String ENV = "SUPERVISOR_REGISTRY_SECRET";

    private final byte[] key;
    private final boolean configured;
    private final String keySource;

    /**
     * 唯一构造器：优先读取环境变量；未配置时使用运行目录下的本地密钥文件。
     *
     * <p>本地文件只在首次启动时生成，权限设为仅当前用户可读写；密钥值不进入源码、
     * 配置、日志或 HTTP 响应。环境变量和本地文件都不可用时才退回进程内随机密钥。
     */
    public RegistrySecurityService() {
        String value = System.getenv(ENV);
        if (value != null && !value.isBlank()) {
            key = value.getBytes(StandardCharsets.UTF_8);
            configured = true;
            keySource = "environment";
            return;
        }
        Path path =
                Path.of(
                        System.getenv().getOrDefault(
                                "SUPERVISOR_REGISTRY_SECRET_FILE", "data/registry-secret"));
        byte[] selected = null;
        String source = null;
        try {
            if (Files.exists(path)) {
                selected = Files.readAllBytes(path);
                source = "local-file";
            } else {
                byte[] generated = new byte[32];
                new SecureRandom().nextBytes(generated);
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(path, generated);
                try {
                    Files.setPosixFilePermissions(
                            path, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // 非 POSIX 文件系统保持平台默认权限。
                }
                selected = generated;
                source = "local-file";
            }
        } catch (IOException error) {
            selected = new byte[32];
            new SecureRandom().nextBytes(selected);
            source = "local-ephemeral";
        }
        key = selected;
        configured = "local-file".equals(source);
        keySource = source;
    }

    /** 对清单文本计算十六进制签名。 */
    public String sign(String manifest) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("清单签名失败", error);
        }
    }

    /** 常量时间比较签名；格式错误直接返回 false。 */
    public boolean verify(String manifest, String signature) {
        if (manifest == null || signature == null) {
            return false;
        }
        byte[] expected = sign(manifest).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = signature.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied);
    }

    public boolean configured() {
        return configured;
    }

    /** 只返回来源枚举，不返回值。 */
    public String keySource() {
        return keySource;
    }

    /** 生成一个不可预测的本地标识；不用于访问控制。 */
    public static String nonce() {
        byte[] value = new byte[8];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
