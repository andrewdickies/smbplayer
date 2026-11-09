package com.smbplayer.tv;

import java.util.TimeZone;

public final class FileDisplayCheck {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        check(FileDisplay.size(-1).equals("大小未知"));
        check(FileDisplay.size(0).equals("0 kB"));
        check(FileDisplay.size(1).equals("1 kB"));
        check(FileDisplay.size(1024).equals("1 kB"));
        check(FileDisplay.size(1025).equals("2 kB"));
        check(FileDisplay.size(1048575).endsWith("kB"));
        check(FileDisplay.size(1048576).equals("1.0 MB"));
        check(FileDisplay.size(5L * 1024 * 1024 * 1024).equals("5120.0 MB"));
        check(FileDisplay.modified(0).equals("修改时间未知"));
        TimeZone old = TimeZone.getDefault();
        try { TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai")); check(FileDisplay.modified(1000).equals("1970-01-01 08:00")); }
        finally { TimeZone.setDefault(old); }
        System.out.println("Size unit boundary, large file, missing metadata and modification time checks passed");
    }
}
