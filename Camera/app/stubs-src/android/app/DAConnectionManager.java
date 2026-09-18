package android.app;

import android.content.Context;

/**
 * 索尼相机私有 API 编译桩（compileOnly，不进 APK）。
 * 运行时由相机系统提供真实实现。
 *
 * v4.31 官方智能遥控逆向结论：退出只调 finish()（AppRoot.finish 默认路径），
 * finishComp 在整个官方反编译树里没有任何调用点。
 */
public class DAConnectionManager {

    public DAConnectionManager(Context context) {}

    // 结束本应用的 DA 会话，相机据此收回控制权并回到取景界面
    public void finish() {}

    // 官方未使用；保留只为兼容历史知识，勿在新代码中调用
    public void finishComp() {}

    public boolean registerCustomKeyInfo(String a, String b, String c) { return false; }
}
