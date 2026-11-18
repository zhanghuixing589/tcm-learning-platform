package cn.tcm.platform.identity.domain;

import java.util.regex.Pattern;

/** 新设置的账户密码规则；登录已有账户时不重新应用注册规则。 */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 12;
    public static final String REGEX = "^(?=.*[A-Z])(?=.*[a-z])(?=.*[^A-Za-z])[\\x21-\\x7E]{10,12}$";
    public static final String MESSAGE = "密码须为10–12位，包含大写字母、小写字母，以及数字或半角特殊符号，不能包含空格或其他语言文字";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private PasswordPolicy() {}

    public static boolean isValid(String password) {
        return password != null && PATTERN.matcher(password).matches();
    }
}
