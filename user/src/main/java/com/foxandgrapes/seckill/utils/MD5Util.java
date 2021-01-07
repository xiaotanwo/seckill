package com.foxandgrapes.seckill.utils;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * MD5工具类
 */
@Component
public class MD5Util {

    private static String saltFrom = "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static Integer saltFromLength = 62;

    private static Integer randomSaltLength = 8;

    public static String md5(String src) {
        return DigestUtils.md5Hex(src);
    }

    private static final String salt = "1a2b3c4d";

    public static String inputPassToFromPass(String inputPass) {
        String str = "" + salt.charAt(0) + salt.charAt(2) + inputPass + salt.charAt(5) + salt.charAt(4);
        return md5(str);
    }

    public static String fromPassToDBPass(String fromPass, String salt) {
        String str = "" + salt.charAt(4) + salt.charAt(5) + fromPass + salt.charAt(2) + salt.charAt(0);
        return md5(str);
    }

    public static String inputPassToDBPass(String inputPass, String salt) {
        String fromPass = inputPassToFromPass(inputPass);
        String dbPass = fromPassToDBPass(fromPass, salt);
        return dbPass;
    }

    /**
     * 获取 randomSaltLength = 8 长度的随机salt
     * @return
     */
    public static String getRandomSalt() {
        Random random = new Random();
        StringBuffer randomSalt = new StringBuffer("");
        for (int i=0; i<randomSaltLength; ++i) {
            randomSalt.append(saltFrom.charAt(random.nextInt(saltFromLength)));
        }
        return randomSalt.toString();
    }

    /**
     * 测试
     * @param args
     */
    public static void main(String[] args) {
        // System.out.println(getRandomSalt());

        // d3b1294a61a07da9b49b6e22b2cbd7f9
        // System.out.println(inputPassToFromPass("123456"));
        // System.out.println(fromPassToDBPass("d3b1294a61a07da9b49b6e22b2cbd7f9", "1a2b3c4d"));
        // System.out.println(inputPassToDBPass("123456", "1a2b3c4d"));
        // b7797cce01b4b131b433b6acf4add449
    }
}
