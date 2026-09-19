package io.github.bi2qfa.sonyconnect.radio;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 无线栈统一包装层 —— 结构照抄官方 Smart Remote Embedded 的
 * com.sony.imaging.app.srctrl.network.wifiWrapper 包：
 * 按系统版本分发（SDK≥16 走 WifiP2pExtManager，旧版走 DirectManager），
 * 把两个平台各自的私有差异翻译成同一套事件与同步调用。
 */
public interface RadioWrapper {

    // ===== 归一化广播 action（由各平台实现翻译后发出）=====
    String ACTION_DIRECT_STATE_CHANGED = "bi2qfa.sony.ftp.radio.DIRECT_STATE_CHANGED";
    String ACTION_GROUP_CREATE_SUCCESS = "bi2qfa.sony.ftp.radio.GROUP_CREATE_SUCCESS";
    String ACTION_GROUP_CREATE_FAILURE = "bi2qfa.sony.ftp.radio.GROUP_CREATE_FAILURE";
    String ACTION_STA_CONNECTED        = "bi2qfa.sony.ftp.radio.STA_CONNECTED";
    String ACTION_STA_DISCONNECTED     = "bi2qfa.sony.ftp.radio.STA_DISCONNECTED";

    // ===== 归一化 Direct 状态（官方 Jb 映射）=====
    int DIRECT_STATE_DISABLED = 1;
    int DIRECT_STATE_ENABLED  = 3;
    int DIRECT_STATE_UNKNOWN  = 5;

    String EXTRA_PREV_STATE = "prev_state";
    String EXTRA_STATE      = "state";
    String EXTRA_CONFIG     = "config";
    String EXTRA_STA_ADDR   = "sta_addr";

    /** 官方常量：使用持久组配置重启 GO */
    int NET_ID_PERSISTENT_GO = -2;

    // ===== 同步操作（内部用 CountDownLatch 等服务应答，超时返回失败）=====

    /** 开/关索尼 Direct 模式。阻塞直至服务应答或超时；返回是否成功。 */
    boolean setDirectEnabled(boolean enable);

    /**
     * 只投递 Direct 关闭命令、不等待服务应答（尽力而为）。
     * 供直接断电的竞速窗口使用：进程随时可能被杀，不能被慢应答堵住。
     */
    void issueDirectOff();

    /**
     * 拆解当前存活的 GO 组（标准 P2P removeGroup 语义）。
     * setDirectEnabled(false) 只关"Direct 功能开关"，本固件实测可能留下仍在
     * 广播的组壳；组必须用 removeGroup 拆，确认以 getLiveGroup()==null 为准。
     */
    void removeGroup();

    /** 查询 Direct 当前是否处于启用状态（探测失败按未启用处理）。 */
    boolean isDirectEnabled();

    /** 当前存活的组信息（SSID/密码/networkId）；无组返回 null。 */
    GroupConfig getLiveGroup();

    /**
     * 命令级启动 GO：成功仅代表指令被接受；
     * 组真正就绪以 ACTION_GROUP_CREATE_SUCCESS 广播为准。
     */
    boolean startGo(int networkId);

    /** 尽力而为的命名配置（SSID 后缀等），失败静默忽略。 */
    void configureIdentity(String ssidPostfix, String modelName, String deviceName);

    /** 最近一次操作失败的细节（用于屏幕诊断），空串表示无信息。 */
    String getLastError();

    /** ===== 组配置载荷 ===== */
    class GroupConfig implements Parcelable {
        public final String ssid;
        public final String preSharedKey;
        public final int networkId;

        public GroupConfig(String ssid, String preSharedKey, int networkId) {
            this.ssid = ssid;
            this.preSharedKey = preSharedKey;
            this.networkId = networkId;
        }

        private GroupConfig(Parcel in) {
            ssid = in.readString();
            preSharedKey = in.readString();
            networkId = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(ssid);
            dest.writeString(preSharedKey);
            dest.writeInt(networkId);
        }

        @Override
        public int describeContents() { return 0; }

        public static final Parcelable.Creator<GroupConfig> CREATOR = new Parcelable.Creator<GroupConfig>() {
            @Override public GroupConfig createFromParcel(Parcel in) { return new GroupConfig(in); }
            @Override public GroupConfig[] newArray(int size) { return new GroupConfig[size]; }
        };
    }
}
