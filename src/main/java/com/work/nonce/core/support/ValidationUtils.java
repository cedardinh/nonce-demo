package com.work.nonce.core.support;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 参数校验工具类，统一参数校验逻辑，减少代码重复
 */
public final class ValidationUtils {

    /**
     * submitter 合法字符集与长度限制：
     * 仅允许大小写字母、数字以及少量分隔符，长度 1~64。
     */
    private static final Pattern SUBMITTER_PATTERN = Pattern.compile("^[a-zA-Z0-9:_-]{1,64}$");

    private ValidationUtils() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 校验字符串参数不为空
     */
    public static String requireNonEmpty(String value, String paramName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(paramName + " 不能为空");
        }
        return value;
    }

    /**
     * 校验对象不为null
     */
    public static <T> T requireNonNull(T value, String paramName) {
        if (value == null) {
            throw new IllegalArgumentException(paramName + " 不能为null");
        }
        return value;
    }

    /**
     * 校验Duration必须大于0
     */
    public static Duration requirePositive(Duration duration, String paramName) {
        requireNonNull(duration, paramName);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(paramName + " 必须大于0");
        }
        return duration;
    }

    /**
     * 校验long值必须非负
     */
    public static long requireNonNegative(long value, String paramName) {
        if (value < 0) {
            throw new IllegalArgumentException(paramName + " 不能为负数");
        }
        return value;
    }

    /**
     * 校验 submitter 的格式与长度，作为 requireNonEmpty 之上的“加强版”校验。
     * <p>约束：长度 1~64，仅允许 [a-zA-Z0-9:_-]。</p>
     */
    public static String requireValidSubmitter(String submitter) {
        requireNonEmpty(submitter, "submitter");
        if (!SUBMITTER_PATTERN.matcher(submitter).matches()) {
            throw new IllegalArgumentException("submitter 非法，只允许 1~64 位的字母、数字、':'、'_'、'-'");
        }
        return submitter;
    }
}

