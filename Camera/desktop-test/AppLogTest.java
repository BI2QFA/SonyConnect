package com.bi2qfa.sonyconnect;









public class AppLogTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(boolean ok, String what) {
        if (ok) {
            pass++;
            System.out.println("[PASS] " + what);
        } else {
            fail++;
            System.out.println("[FAIL] " + what);
        }
    }

    public static void main(String[] args) {
        
        AppLog.i("T", "第一条");
        AppLog.i("T", "第二条");
        AppLog.w("T", "第三条");
        check(AppLog.size() == 3, "size 记到 3");
        String all = AppLog.dump(0);
        check(all.split("\n").length == 3, "dump(0) 给全部 3 行");
        check(all.indexOf("第一条") >= 0 && all.indexOf("第一条") < all.indexOf("第三条"),
                "顺序是老 → 新");
        check(all.indexOf("I/T") >= 0 && all.indexOf("W/T") >= 0, "级别 I/W 都带上");
        String tail2 = AppLog.dump(2);
        check(tail2.split("\n").length == 2
                        && tail2.indexOf("第一条") < 0
                        && tail2.indexOf("第三条") >= 0,
                "dump(2) 只给尾部 2 行（最老的被截掉）");

        
        for (int i = 0; i < 2000; i++) {
            AppLog.i("B", "第 " + i + " 条");
        }
        check(AppLog.size() == 800, "容量封顶在 800（灌了 2000 条）");
        String after = AppLog.dump(0);
        String[] lines = after.split("\n");
        check(lines.length == 800, "dump(0) 绕圈后仍是 800 行");
        check(after.indexOf("第一条") < 0, "最早的日志已被环形覆盖");
        check(lines[0].indexOf("第 1200 条") >= 0, "留下的最早一条 = 2000-800 = 第 1200 条");
        check(lines[799].indexOf("第 1999 条") >= 0, "最后一条是最新写入的");
        
        boolean ordered = true;
        for (int i = 1; i < lines.length; i++) {
            int a = numOf(lines[i - 1]);
            int b = numOf(lines[i]);
            if (a < 0 || b < 0 || b != a + 1) {
                ordered = false;
                break;
            }
        }
        check(ordered, "绕圈之后行序仍严格递增（无错位）");

        
        AppLog.e("T", "出错了", new IllegalStateException("boom"));
        check(AppLog.dump(1).indexOf("IllegalStateException") >= 0
                        && AppLog.dump(1).indexOf("boom") >= 0,
                "e(tag,msg,throwable) 带上了异常类型与消息");

        System.out.println("AppLogTest PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) {
            throw new RuntimeException("AppLog 回归失败");
        }
    }

    
    private static int numOf(String line) {
        try {
            int a = line.indexOf("第 ");
            int b = line.indexOf(" 条");
            if (a < 0 || b < 0 || b <= a + 2) {
                return -1;
            }
            return Integer.parseInt(line.substring(a + 2, b).trim());
        } catch (Throwable t) {
            return -1;
        }
    }
}
