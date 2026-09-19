package com.bi2qfa.sonyconnect;

import android.app.Activity;
import android.app.DAConnectionManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.sony.scalar.sysutil.ScalarInput;
import com.sony.scalar.sysutil.ScalarProperties;

import java.io.File;
import java.io.IOException;
import java.util.List;

import com.bi2qfa.sonyconnect.radio.RadioWrapper;
import com.bi2qfa.sonyconnect.radio.RadioWrapperFactory;

/**
 * SonyConnect 相机端主界面。
 *
 * 单 Activity 多屏幕（相机无返回键、无触摸屏）：主页面 / 选项菜单 / 连接设置 /
 * 配对模式 / 已配对设备 / 关于 / 通用确认弹窗，全部叠在 activity_main.xml 的 FrameLayout
 * 里切换可见性。按键按 scanCode 分发（方向轮=DPAD、中键=ENTER、MENU 键进选项）。
 *
 * 两种连接方式：
 * - 相机热点：整体移植 SonyFTP v2.0 验证过的官方无线电状态机。此模式的
 *   Wi-Fi/Direct 归本应用接管，退出时按铁律全关并确认。
 * - Wi-Fi 客户端：启动即自动打开 Wi-Fi 总开关（用户要求；不触碰 Direct 状态机），
 *   轮询关联态与 DHCP IP，就绪即起服务；菜单"Wi-Fi 设置"行可随时跳系统设置。
 *   此模式的 Wi-Fi 是用户自己的网络，退出时不关（只收走本应用的服务）。
 *
 * 切换连接方式 = 完整走一遍退出流程（停服务、无线电归位、恢复 APO）后再启动
 * 新模式（用户要求）。
 *
 * 退出纪律（铁律）：停 PTP/IP（先停缩略图预取线程释放 SD 句柄，再有序拆
 * 数据连接→协议/事件连接→监听→令牌表）→ [热点模式] 同步关 Direct → 关 WiFi → 确认沉降 → 恢复自动关机 → 才
 * 通知相机结束应用；ExitCompleted 广播兜底同一套，清理完成后才允许进程死亡。
 */
public class MainActivity extends Activity {

    // ===== 屏幕状态 =====
    private static final int SCR_MAIN = 0;
    private static final int SCR_MENU = 1;
    private static final int SCR_MODE = 2;
    private static final int SCR_ABOUT = 3;
    private static final int DLG_CONFIRM = 4;   // 通用确认弹窗（退出 / 解除配对 / 进配对模式）
    private static final int DLG_DEFAULT = 5;
    private static final int DLG_EXITING = 6;  // 正在退出指示（无按钮）
    private static final int SCR_PAIRING = 7;  // 配对模式（进入即开窗，只显示码与倒计时）
    private static final int SCR_PAIRED = 8;   // 已配对设备（与配对界面是两个界面）
    private static final int SCR_LOG = 9;      // 调试日志（隐藏入口：关于页连按十下确定键）
    /**
     * 当前屏。**初值 -1 = "还没有显示出任何一屏"**。
     *
     * <p>不能默认成 SCR_MAIN：XML 里 screen_main 确实默认可见，但"它算第几层"取决于启动路径 ——
     * 有连接方式时它是第一层（MENU=退出），首启时第一屏是连接设置面板（主界面根本没露过脸）。
     * 假装已经站在主界面上，首启的连接设置就会被记成"从主界面来的"，MENU 会回主界面而不是退出。
     */
    private int screen = -1;

    /**
     * MENU 键的**层级历史**（用户定版的铁律：MENU = 返回上一层级，也就是**上一次显示的那个界面**；
     * 已经处于第一层、没有可返回的界面时，MENU = 退出软件）。
     *
     * <p>★ 上一版是把"上一级"**一屏一个字段**写死的（pairingBack / confirmBack / aboutFromPairing…）：
     * 十个屏就有十处判据，每加一屏都要替它想一遍"它从哪来"，而且总会在某条冷路上回错地方。
     * 现在只留这一份历史 —— 由 {@link #showScreen} 在**每次真的换了屏**时记下"从哪一跳来的"，
     * MENU 一律弹栈，屏自己不再关心来路。
     *
     * <p>浮层（确认框/通知框/切换浮层）**不入栈**：它的上一级就是它压着的那一屏，由
     * {@link #baseScreen} 记着 —— MENU 在浮层上只做"关掉它"，不执行浮层上的动作。
     */
    private final java.util.List<Integer> navHistory = new java.util.ArrayList<Integer>();
    /** 层级上限：正常导航深度个位数就够，超了丢最旧的一层（最近几层才是 MENU 要用的）。 */
    private static final int NAV_HISTORY_MAX = 12;
    /**
     * 最近一次显示的**基础屏**（不含浮层）。
     *
     * <p>两处用它：① 浮层的"上一级"（MENU 关浮层、点取消、通知确定之后回到哪一屏）；
     * ② 从浮层出发跳到别的基础屏时，该入栈的是它 —— 浮层只是过路，它底下那一屏才是来路。
     * 它同时取代了原来手工维护、还要在 {@code promptPairingRequired} 里特判的 confirmBack。
     */
    private int baseScreen = -1;

    /**
     * "这一次 showScreen 是把配对页**立成家**"（由 {@link #enterPairingHome} 置位）。
     *
     * <p>它只回答一个问题：**这次进配对页要不要先弹"请先与一台手机配对"**。
     * 无已配对设备时，配对页是这一态的家（应用把用户放进来的），立屏这一步不算
     * "从别处进配对页"——提示由调用方马上盖上去（或者刚刚才问过）。
     * 用户自己从菜单进来那一支本机有设备，压根不走这条判断。
     */
    private boolean enteringPairingHome;
    /** 配对页是不是**已经**是当前这一屏（关掉盖在它上面的浮层 = 回到它，不是进它）。 */
    private boolean pairingPageIsCurrent() {
        return screen == SCR_PAIRING || baseScreen == SCR_PAIRING;
    }

    /**
     * 用户**已经进过配对流程**了吗（"配对页是他自己走到过的地方"）。
     *
     * <p>判据：配对页还在层级历史里（他从那一屏走出来，还没回到它）；或者这次就是
     * <b>MENU 返回</b>到配对页 —— 返回的目标一定是他上次看过的那一屏，而配对页
     * 只可能是他走进去过（showScreen 会先把他带到那儿）才会进历史。
     *
     * @param requested 本次调用**原本**要去的屏（守卫①改写 target 之前记下的那个）
     */
    private boolean pairingFlowVisited(int requested, boolean back) {
        return navHistory.contains(SCR_PAIRING) || (back && requested == SCR_PAIRING);
    }

    // 确认弹窗动作
    private static final int CONFIRM_EXIT = 0;
    private static final int CONFIRM_UNPAIR = 1;
    private static final int CONFIRM_PAIRING = 2;   // "进入配对模式"的二次确认
    private int pendingConfirm = CONFIRM_EXIT;
    private String pendingUnpairId = null;
    // 确认弹窗的"上一级"不再单独记一个字段：它就是弹窗压着的那一屏（baseScreen）。
    // 原先的 confirmBack 还要在 promptPairingRequired 里为"浮层上弹浮层"特判一次，
    // 而现在 baseScreen 天然就是"最近一次显示的基础屏"，那个特判随之消失。

    // ===== 运行阶段（显示用状态机） =====
    private static final int PH_STARTING = 0;
    private static final int PH_RUNNING = 1;
    private static final int PH_FAILED = 2;
    private static final int PH_CLOSING = 3;
    private int phase = PH_STARTING;

    // ===== 地址（端口由 PtpIpServer 自选：15740 起，绑不上依次回退）=====
    static final String HOTSPOT_IP = "192.168.122.1"; // Wi-Fi Direct GO 固定地址

    // 官方同款机制：WiFi 关净后再重开（官方等 5 秒；为切换提速缩到 2 秒，
    // 万一驱动沉降慢导致使能失败，由既有的重试链兜底）
    private static final long DELAY_WIFI_ENABLE_MS = 2000;
    private static final long DELAY_FATAL_CHECK_MS = 5000;
    private static final long EXIT_CONFIRM_CAP_MS = 10000;
    private static final int STATION_POLLS_MAX = 120;       // Wi-Fi 客户端轮询上限（500ms × 120 = 60s，含使能+关联+DHCP）

    // ===== 连接方式 =====
    static final String MODE_WIFI = "wifi";
    static final String MODE_HOTSPOT = "hotspot";
    private static final String PREFS = "connect";
    private static final String KEY_MODE = "mode";

    private SharedPreferences prefs;
    private final Handler handler = new Handler();

    private boolean shuttingDown = false;
    private boolean jumpingToSystem = false; // 跳系统网络设置期间 onPause 不得触发强退
    private boolean startedOnce = false;     // 本次进程已按默认方式启动过服务

    // 焦点
    private int menuFocus = 0;
    private int modeFocus = 0;
    private int confirmFocus = 0;
    private int defFocus = 0;

    // 模式选择流
    private String pendingMode = null;      // 已选、待回答"设为默认"

    // 已配对设备页：行模型 = 已配对手机各一行；中键解除（底栏用中键图标提示，行里不写文字）。
    // （设备清单**不在配对页**上了 —— 配对与"已配对设备"是两个界面，用户要求。）
    private int pairedFocus = 0;
    /** 已配对设备列表上一帧渲染的台数：与当前不符说明刚有人配对成功，需要重建列表。 */
    private int pairedShownCount = -1;
    private String[][] pairedItems = new String[][]{{"无已配对设备", ""}};
    /** 配对页心跳：只在配对页跑，负责刷倒计时与"配对已结束"提示。 */
    private final Runnable pairingTicker = new Runnable() {
        public void run() {
            // 只在配对页跑；离开这一页就彻底停（不重排）
            if (screen != SCR_PAIRING) {
                return;
            }
            // ★ 切换连接方式/收尾期间**只跳过刷新、不停止心跳**：新流程允许"选完连接方式
            //   不等服务就绪就进配对页"，那一刻 shuttingDown 还是 true（切换中）—— 老写法
            //   直接 return 且不重排，心跳就此死掉，配对页的"剩余 N 秒"会永远停在某一下。
            if (!shuttingDown) {
                updatePairingScreen();
            }
            handler.postDelayed(this, 1000);
        }
    };

    /** 已配对设备页心跳：台数变了就重建列表（配对成功会从别处改变它）。 */
    private final Runnable pairedTicker = new Runnable() {
        public void run() {
            if (screen != SCR_PAIRED || shuttingDown) {
                return;
            }
            if (pairingStore != null && pairingStore.size() != pairedShownCount) {
                int keepFocus = pairedFocus;
                rebuildPairedItems();
                pairedFocus = keepFocus;
                ((BaseAdapter) pairedList.getAdapter()).notifyDataSetChanged();
            }
            handler.postDelayed(this, 1000);
        }
    };

    // 主屏刷新（1s）：手机端"连上/断开"是外部事件（TCP 建连与断开），相机端没有
    // 回调可挂，只能轮询。只在主屏可见时刷，且**状态没变就不碰视图** —— 否则每秒
    // 重渲一次二维码位图，白吃相机那点 CPU。
    private String lastMainStatus = null;
    /**
     * 上一次刷主屏时"手机端"那行的快照（型号名，没连着就是"未连接"）。
     *
     * <p>★ 这个字段是"手机断开后主界面不更新"的修复点。原先这个心跳**只比状态文字**、
     * 变了才 {@code statusView.setText()}，而**手机图标、六个点、图标下那行型号**
     * 全都只由 {@link #refreshScreens()} 刷 —— 它只在切屏（{@code showScreen(SCR_MAIN)}）、
     * 配对成功、按键切正文块这些时刻被调用。于是手机一断，状态文字（若停在"服务状态"块）
     * 会变，但图标还亮着、型号还写着，**必须离开主界面再回来才更新**（用户实测）。
     * 连接状态是"外部事实"，只能靠轮询发现，所以这个心跳必须驱动整块主屏，
     * 不能只驱动状态文字。
     */
    private String lastPhoneCaption = null;
    private final Runnable mainTicker = new Runnable() {
        public void run() {
            if (shuttingDown) {
                return;
            }
            // 主界面与服务状态块**共用同一份正文**（两屏各有一块），所以心跳必须两屏都跑 ——
            // 否则在配对页翻到"服务状态"那一块，看到的会是进屏那一刻的旧文本（服务是后台
            // 起来的，等它就绪时用户多半已经在配对页上了）。
            if (screen == SCR_MAIN || screen == SCR_PAIRING) {
                String now = buildMainStatusText();
                boolean textChanged = !now.equals(lastMainStatus);
                // 比"手机端"那一行的快照：它同时编码了"有没有连着"和"连的是谁"。
                String caption = phoneModelText();
                boolean linkChanged = lastPhoneCaption == null
                        || !lastPhoneCaption.equals(caption);
                if (textChanged) {
                    if (lastMainStatus != null) {
                        AppLog.i("UI", "状态变化 → " + now.replace((char) 10, ' '));
                    }
                    lastMainStatus = now;
                    updateStatusTexts();
                    // 热点名/密码、二维码都随 phase 与热点信息走，跟状态文本同源：
                    // 状态一变就一起刷（它们各自只在真有变化时才碰视图）。
                    updateHotspotTexts();
                    updateQrCode();
                }
                if (screen == SCR_MAIN && linkChanged) {
                    AppLog.i("Net", "手机端一行变化 → " + caption);
                    lastPhoneCaption = caption;
                    // 整块刷（图标 on/off、六个点、型号行、正文块）
                    refreshScreens();
                }
            }
            // 顶栏 Wi-Fi 指示也跟着轮询：无线电就绪（PH_RUNNING）是外部事件，
            // 相机端没有回调可挂，只能由这个 1s 心跳兜住。
            refreshWifiIcon();
            // 相机图标与它同一条判据、同一个心跳（用户要求：热点就绪时相机图标才亮，
            // 也就是"wi-fi 图标亮起的时候"）—— 两处分开刷迟早会出现
            // "wifi 亮了、相机还暗着"这种自相矛盾的画面。
            refreshCameraIcon();
            handler.postDelayed(this, 1000);
        }
    };

    /**
     * 配对事件 → UI。回调来自 PTP/IP 的连接线程，所以一律 post 回主线程。
     */
    private class PairingUiEvents implements PtpCameraHandler.PairingEvents {

        public void onPaired(final String peerDeviceName) {
            AppLog.i("Pair", "配对成功：手机=" + peerDeviceName);
            handler.post(new Runnable() {
                public void run() {
                    // 配对成功即自动回主页面（用户定版）：把用户留在配对页会让人以为
                    // 没成功 —— 而主屏那行"手机端：已连接（名字）"才是成功的证据。
                    // 回主界面 = 回到第一层，navHistory 由 showScreen 清空。
                    if (screen == SCR_PAIRING) {
                        leavePairingMode();
                        modeFromPairing = false;
                        showScreen(SCR_MAIN);
                        updateMainStatus();
                    }
                    refreshMenuValues();
                }
            });
        }

        public void onPairingAttemptFailed(final int used, final int max, final boolean windowClosed) {
            AppLog.w("Pair", "配对码错误 " + used + "/" + max + "，窗口关闭=" + windowClosed);
            if (!windowClosed) {
                return;
            }
            handler.post(new Runnable() {
                public void run() {
                    if (shuttingDown) {
                        return;
                    }
                    // 用满次数 → **换一张新码**，再弹窗告知。
                    // ★ 换码这件事在这里**显式**做（openWindow() 每次生成新码，见
                    //   PairingStore）：以前是靠"配对页上弹浮层会关掉配对窗、回屏时重开"
                    //   这个副作用捎带着换的 —— 现在浮层不再关窗（那张码在用户念它的
                    //   期间必须稳定），所以换成明说：提示的字面是"已自动更换新的配对码"，
                    //   那就真的在弹提示之前把码换掉。
                    if (pairingStore != null) {
                        pairingStore.openWindow(System.currentTimeMillis());
                    }
                    // 这是"警告"性质（原厂带 exclamation 图标），所以 warn=true。
                    showNotice("配对码已连续错误 " + max + " 次\n已自动更换新的配对码", "确定",
                            NOTICE_GOTO_PAIRING, true);
                }
            });
        }
    }

    // 视图
    private View screenMain, screenMenu, screenMode, screenAbout, screenPairing, screenPaired,
            screenLog, dlgConfirm, dlgDefault, dlgExiting;
    private TextView statusView, confirmMsg, qrCaption, exitMsgView;
    /** 关于页三块（用户要求拆开）：① 应用名+版本 ② 配套版本提示+开发者 ③ 本机设备码 */
    private TextView aboutTitle, aboutMeta, aboutCode;
    private ImageView qrView;
    /** 日志屏正文与滚动容器（进屏滚到底，确定键刷新）。 */
    private TextView logText;
    private ScrollView logScroll;
    private TextView btnConfirmOk, btnConfirmCancel, btnDefaultOk, btnDefaultNo;
    private TextView pairingCodeText, pairingCountdownText, pairingHintText;
    private ListView menuList, modeList, pairedList;
    /** 原厂四档电池图（ScalaA buttery1..4），不再自绘；主界面与配对页各一份 */
    /**
     * 五个屏的顶栏 id —— 它们都是 {@code screen_header.xml} 的 include，
     * 电量控件在里面同名，所以按"先取顶栏、再在它子树里取"的方式刷新（见 updateBattery）。
     * 这样不会出现"主界面刷了、别的屏没刷"。
     */
    private static final int[] HEADER_IDS = {
            R.id.header_main, R.id.header_menu, R.id.header_paired,
            R.id.header_pairing, R.id.header_about, R.id.header_log,
    };
    /** 通知态的警告图标（原厂 p_dialogwarning 60×60 @290,20） */
    private ImageView dlgConfirmWarn;

    // ===== 主界面与服务状态块（两屏共用同一套结构）=====
    /**
     * 正文区是三块**互斥**内容，常驻的只有下方那排按钮（用户定版）：
     * <pre>
     *   0 连接示意（默认）  左=连接方式，右=手机型号
     *   1 热点信息          左=热点名称与密码，右=二维码（仅热点模式；接入点模式该键变成"Wi-Fi 设置"）
     *   2 服务状态          现有 buildMainStatusText() 的多行文本
     * </pre>
     *
     * <p>★ 配对页（SCR_PAIRING）是**同一套结构**（用户要求："配对界面下也应该显示类似于
     * 主页面的按钮"），只是第 0 块换成配对码。两屏的正文/按钮在代码里由同一组方法填
     * （{@link #refreshScreens} 一族），内容永远一致；只有 XML 里的控件是两份
     * —— 同一个 Activity 里同 id 的控件不能有两份，findViewById 只会认第一个。
     */
    private View mainBlockDiagram, mainBlockHotspot, mainBlockStatus;
    /** 手机图标与连线点阵：都随连接状态在 on/off 两张原厂图之间切（off 是暗的） */
    private ImageView mainIconPhone, mainIconDots;
    /** 左边那台相机：随**无线电是否就绪**在 on/off 之间切（判据见 refreshCameraIcon） */
    private ImageView mainIconLeft;
    /** 当前已设的手机图标资源（0=还没设过），避免每秒 ticker 白重画一次 */
    private int mainPhoneIconRes = 0;
    /** 当前已设的相机图标资源（同上） */
    private int mainCameraIconRes = 0;
    private TextView mainCapLeft, mainCapRight, btnMainLeft, btnMainRight;
    /** 热点信息块的名称与密码两行（主界面 / 配对页各一份） */
    private TextView mainHotName, mainHotPass, pairingHotName, pairingHotPass;
    /** 主界面按钮焦点：0=左键 1=右键 */
    private int mainFocus = 0;
    /** 当前正文块：0=连接示意 1=热点信息 2=服务状态 */
    private int mainBlock = 0;

    // ===== 配对页的那一套（结构与主界面一一对应，见上面的 ★）=====
    private View pairingBlockCode, pairingBlockHotspot;
    private TextView pairingStatus;
    private ImageView pairingQrView;
    private TextView pairingQrCaption, btnPairingLeft, btnPairingRight;
    /** 配对页按钮焦点：0=左键 1=右键（与 mainFocus 同一套语义，各屏各记） */
    private int pairingFocus = 0;
    /** 配对页当前正文块：0=配对码 1=热点信息 2=服务状态 */
    private int pairingBlock = 0;

    // 电量
    private int batteryPct = -1;
    /** 上次记进日志的电量（避免每秒刷屏）。 */
    private int lastLoggedBattery = -1;

    // ===== 菜单模型：{标题, 当前值, 动作码} =====
    //
    // ★ **按动作码分发，不按下标**（这一条是本轮改的）：菜单有两个来路 —— 主界面
    //   （正常菜单）与配对页（配对模式下"进入配对模式"这一项是**隐藏**的，用户要求），
    //   两份模型的项数不同，下标会在两处之间错位。行布局只用第 0/1 列，第 2 列是纯逻辑。
    private static final String ACT_MODE = "mode";        // 连接设置（选连接方式）
    private static final String ACT_PAIR = "pair";        // 进入配对模式
    private static final String ACT_PAIRED = "paired";    // 已配对设备（清单页）
    private static final String ACT_ABOUT = "about";
    private static final String ACT_EXIT = "exit";

    // "进入配对模式"与"已配对设备"是**两个独立的项**（用户要求）：
    //   前者是一个动作入口（带二次确认弹窗，防误触），后者是设备清单页。
    // 历史项"清除软件数据"已**彻底删除**（用户要求）——连同 CONFIRM_WIPE 与
    // wipeModeAndRechoose() 一起拿掉，不留半截功能。改连接方式的入口在"连接设置"里。
    private final String[][] menuItems = {
            {"连接设置", "未设置", ACT_MODE},
            {"进入配对模式", "", ACT_PAIR},
            {"已配对设备", "无", ACT_PAIRED},
            {"关于", "", ACT_ABOUT},
            {"退出应用程序", "", ACT_EXIT},
    };

    /**
     * **配对模式下的菜单**（用户要求）：与上面那份只差一项 —— "进入配对模式"隐藏
     * （已经在配对模式里了，这一项没有任何意义）。
     *
     * <p>★ 这与换装那版被我删掉的"精简菜单"**不是一回事**：那份是另一套项
     * （连接模式选择 / Wi-Fi 设置 / 关于 / 退出），是"待机态专用菜单"；
     * 这一份只是在配对模式里藏掉那一项，其余一字不差。
     *
     * <p>它同时也是"有没有必要藏"的判据来源：**菜单是从配对页打开的**就藏
     * （见 {@link #activeMenu()}）。而"没有已配对设备"这一态下用户必然在配对页上
     * —— 主界面在本机没有已配对设备时根本立不起来（showScreen 的守卫），
     * 所以"无设备 ⟹ 在配对模式里"这条链是闭合的，不用再单独判一次设备数。
     */
    private final String[][] menuItemsPairingMode = {
            {"连接设置", "未设置", ACT_MODE},
            {"已配对设备", "无", ACT_PAIRED},
            {"关于", "", ACT_ABOUT},
            {"退出应用程序", "", ACT_EXIT},
    };

    /** 当前该用哪份菜单：菜单是从配对页按垃圾桶键打开的就用"配对模式版"。 */
    private String[][] activeMenu() {
        return menuFromPairing ? menuItemsPairingMode : menuItems;
    }

    /** 按下标取动作码（越界返回 null：行数在两份模型之间会变，宁可什么都不做也别崩）。 */
    private String menuAction(int pos) {
        String[][] model = activeMenu();
        if (pos < 0 || pos >= model.length) {
            return null;
        }
        return model[pos][2];
    }

    /**
     * 菜单上按确定：**看动作码，不看下标** —— 两份模型项数不同，下标会错位。
     */
    private void onMenuActivated(int pos) {
        // 离开这一项之后回哪一屏：从配对页进来的菜单，选完连接方式要回配对页
        // （modeFromPairing）；其余各屏的"上一级"由 navHistory 决定。
        String act = menuAction(pos);
        AppLog.i("UI", "菜单选中第 " + pos + " 项（动作=" + act + "）");
        if (ACT_MODE.equals(act)) {
            modeFromMenu = true;
            modeFromPairing = menuFromPairing;
            showScreen(SCR_MODE);
        } else if (ACT_PAIR.equals(act)) {
            // "进入配对模式"：进门统一走 requestPairing() —— 已有设备弹"会断开手机"的
            // 二次确认，**一台都没有则先弹"请先与一台手机配对"**（用户定版，两者不能互换）。
            requestPairing();
        } else if (ACT_PAIRED.equals(act)) {
            showScreen(SCR_PAIRED);
        } else if (ACT_ABOUT.equals(act)) {
            showScreen(SCR_ABOUT);
        } else if (ACT_EXIT.equals(act)) {
            showConfirm(CONFIRM_EXIT);
        }
    }

    /**
     * 连接设置：**只有两个真正的选项**（用户要求删掉"Wi-Fi 设置"那一行）。
     * 跳系统 Wi-Fi 设置的入口还剩一个：主界面在接入点模式下的左键。
     *
     * <p>★ 原配对待机态那份"精简菜单"（连接模式选择 / Wi-Fi 设置 / 关于 / 退出软件）
     * 已按用户要求**整份删除**：配对页看到的菜单与主界面完全一致，只有一份模型。
     * 代价是热点模式下不再有进系统 Wi-Fi 设置的那一项（接入点模式下主界面左键仍在）。
     */
    private final String[][] modeItems = {
            {"连接到 Wi-Fi 网络", ""},
            {"使用相机热点", ""},
    };

    private String savedMode = null;

    // ===== 无线电（热点模式）/ 服务链 =====
    private WifiManager wifiManager;
    private RadioWrapper radio;

    private PtpIpServer ptpServer;
    private PtpCameraHandler ptpHandler;
    private PairingStore pairingStore;
    private ThumbPrefetcher thumbPrefetcher;

    private volatile String ssid = null;      // 热点：DIRECT-xxx；Wi-Fi 模式：所连网络名
    private volatile String password = null;  // 仅热点模式
    private volatile String currentIp = null; // 对外服务地址（热点固定；Wi-Fi 模式 DHCP）
    private String errorMsg = null;

    // ===== 官方标志位（热点链，SonyFTP v2.0 原样） =====
    private boolean isInitialDisabling;
    private boolean isDisableActionFiltered;
    private boolean isEnableActionFiltered;
    private boolean isGroupCreateActionFiltered;
    private boolean isDisablingForFinish;
    private boolean isRetrying;
    /** Direct 使能命令的独立重试标志（与官方 isRetrying 分离，避免语义混淆） */
    private boolean isDirectEnableRetrying;
    /** 自动整链恢复次数（每次用户手动触发时清零） */
    private int watchdogRecoveries = 0;

    private int curWifiMgrState = WifiManager.WIFI_STATE_ENABLED;
    private int curWifiDirectMgrState = RadioWrapper.DIRECT_STATE_UNKNOWN;

    private BroadcastReceiver receiver;
    private IntentFilter iFilter;

    /** Wi-Fi 客户端模式关联轮询 */
    private int stationPolls = 0;
    private int ssidRetries = 0;
    private boolean stationNudged = false;
    /** Wi-Fi 模式：null=尚未判定；TRUE=启动时 Wi-Fi 是关的、由我们打开（退出时按铁律关回）；FALSE=原本就开着（退出不动） */
    private Boolean wifiEnabledByUs = null;
    private boolean stationWaiting = false;
    /** 切换连接方式期间为 true：PH_CLOSING 文案显示"正在切换"而非"正在关闭" */
    private boolean modeSwitching = false;
    /** 连接设置页来路：true=从选项菜单进入（MENU 回主界面）；false=首启/清除后（MENU=退出） */
    private boolean modeFromMenu = false;
    /**
     * 选项菜单的来路：true = 从**配对页**按垃圾桶键（C2）进来的。
     *
     * <p>★ 这个字段取代了原先的"配对待机态" {@code pairingStandby}。老写法要解决的是
     * "从配对页进菜单再选连接方式，选完该回配对页而不是被甩到主界面"，但它把"精简菜单"
     * 也一起绑在了同一个布尔上；用户随后定版：**配对页的菜单与主界面完全一致，不要精简版**
     * （见 {@link #onMenuActivated}）。菜单合成一份之后，"回哪一屏"就只能看**从哪一屏进来**，
     * 于是这个字段只留"来路"这一个语义。
     *
     * <p>配对页那一侧同样是垃圾桶键进来的（用户把配对页的 MENU 改成了"返回"）。
     */
    private boolean menuFromPairing = false;

    // pairingBack 已删（本轮 MENU 铁律）：配对页的"上一级"不再靠"进页时记一个字段"，
    // 而是由 navHistory 弹栈得到（见 showScreen / goBackOneLevel）。
    // 老写法要手写四个赋值点（首启、菜单进来、通知确定、确认框确定），漏一个就回错地方。

    /** 隐藏入口：关于页连按多少下确定键进日志屏。 */
    private static final int ABOUT_ENTER_COUNT = 10;
    /** "连续"的判据：两次确定键间隔超过这个毫秒数就从头数。 */
    private static final long ABOUT_ENTER_WINDOW_MS = 2000;
    /** 日志屏一屏最多铺多少行（内存里存 800，屏幕上铺尾部 300 就够看）。 */
    private static final int LOG_SHOW_LINES = 300;
    private int aboutEnterCount;
    private long aboutEnterLast;

    /** 本机是否已有已配对设备（配对表在 onCreate 就建好了，服务没起也能问）。 */
    private boolean hasPairedDevice() {
        return pairingStore != null && pairingStore.size() > 0;
    }
    /** 连接设置页来路：true=从配对页菜单进来（选完**回配对页**，见 finishModeChoice）。 */
    private boolean modeFromPairing = false;

    /**
     * 切换连接方式跑完后该落到哪一屏。
     *
     * <p>★ 这个字段是"首启引导坏了"的修复点。原先 {@code beginModeSwitch()} 的完成回调
     * **写死** {@code showScreen(SCR_MAIN)}：于是首次启动选完连接方式，界面被那一下
     * 强行甩到主界面，绕过了"该进配对流程"的判断 —— 用户看到的正是这个。
     * 现在目标由发起方（{@link #finishModeChoice}）给定，回调只负责落地。
     */
    private int modeSwitchTarget = SCR_MAIN;

    // modeSwitchPairingPrompt 已删（2026-09-13）：它记的是"首启专属的那次提示"，
    // 但落地判据现在是**铁律**——本机没有已配对设备就走配对流程（见 landAfterModeChoice
    // 与 beginModeSwitch 的完成回调）。首启必然没有设备，所以那条标志被这条判据完全覆盖，
    // 留着只会多一个"写了没人读"的字段。
    // aboutFromPairing 已删（本轮 MENU 铁律）：它只服务"关于页 MENU 回配对页"这一条，
    // 而 MENU 现在一律走 navHistory 弹栈（从配对页菜单进来的关于页，栈顶就是选项菜单）。

    private static MainActivity sInstance;

    // ===== 官方 AppInfo 键表（BaseApp 原文，退出后回取景界面必需） =====
    private static final String[] PULLING_BACK_KEYS_FOR_PLAYBACK = {
            "KEY_S2", "KEY_S1_1", "KEY_S1_2", "KEY_MOVREC", "KEY_MODE_DIAL", "KEY_USB_CONNECT"};
    private static final String[] RESUME_KEYS_FOR_SHOOTING = {
            "KEY_POWER_SLIDE_PON", "KEY_RELEASE_APO", "KEY_PLAY_APO", "KEY_MEDIA_INOUT_APO",
            "KEY_LENS_APO", "KEY_ACCESSORY_APO", "KEY_DEDICATED_APO", "KEY_POWER_APO", "KEY_PLAY_PON"};

    // ===== 无线电事件 Runnable（热点链，SonyFTP v2.0 原样） =====

    private final Runnable delayedWifiEnabler = new Runnable() {
        public void run() {
            try {
                wifiManager.setWifiEnabled(true);
            } catch (Throwable t) {
            }
        }
    };

    private final Runnable delayedFatalCheck = new Runnable() {
        public void run() {
            if (curWifiMgrState == WifiManager.WIFI_STATE_DISABLED
                    || curWifiDirectMgrState == RadioWrapper.DIRECT_STATE_DISABLED) {
                String d = "";
                try {
                    d = radio.getLastError();
                } catch (Throwable t) {
                }
                goFatal("无线模块未能启动" + (d != null && d.length() > 0 ? "\n" + d : ""));
            }
        }
    };

    /** Direct 使能应答超时后的二次尝试；再败才报致命（官方无限等 + 我们的兜底） */
    private final Runnable delayedDirectEnableRetry = new Runnable() {
        public void run() {
            if (shuttingDown || phase != PH_STARTING
                    || curWifiDirectMgrState == RadioWrapper.DIRECT_STATE_ENABLED) return;
            boolean acked = false;
            try {
                acked = radio.setDirectEnabled(true);
            } catch (Throwable t) {
            }
            if (!acked) {
                goFatal("无法启用Direct模式");
            }
        }
    };

    /** 整条启动链的看门狗：卡死超过时限先自动重跑一轮，仍不行才报致命 */
    private final Runnable stalledStartupCheck = new Runnable() {
        public void run() {
            if (shuttingDown || phase != PH_STARTING) return;
            if (watchdogRecoveries < 1) {
                watchdogRecoveries++;
                initAndStartWifiCycle(false);
            } else {
                goFatal("启动超时");
            }
        }
    };

    // ===== 生命周期 =====

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sInstance = this;

        AppLog.i("Life", "onCreate：版本 " + DeviceInfo.getFirmwareVersion()
                + " 界面 vc 见 build.gradle，sdk=" + Build.VERSION.SDK_INT);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        savedMode = prefs.getString(KEY_MODE, null);
        AppLog.i("Life", "onCreate：上次连接方式=" + savedMode);

        // ★ 配对表在这里就建好（原来在 startServices() 里懒建）。
        //   它是纯文件存储，既不依赖无线电也不依赖 PTP/IP；而"本机有没有已配对设备"
        //   这件事**必须在服务起来之前**就能回答 —— 首启选完连接方式要立刻决定是否直接
        //   进配对页（那一刻服务还在后台起），懒建的话 pairingStore 还是 null，
        //   判断永远为假，界面就停在主界面不动（用户实测反馈的正是这个）。
        pairingStore = new PairingStore(getFilesDir());

        screenMain = findViewById(R.id.screen_main);
        screenMenu = findViewById(R.id.screen_menu);
        screenMode = findViewById(R.id.screen_mode);
        screenAbout = findViewById(R.id.screen_about);
        screenLog = findViewById(R.id.screen_log);
        screenPairing = findViewById(R.id.screen_pairing);
        screenPaired = findViewById(R.id.screen_paired);
        dlgConfirm = findViewById(R.id.dlg_exit);
        dlgDefault = findViewById(R.id.dlg_default);
        dlgExiting = findViewById(R.id.dlg_exiting);

        statusView = (TextView) findViewById(R.id.status_view);
        // 顶栏是 include，电量控件在各自子树里，统一遍历刷新（见 updateBattery）

        aboutTitle = (TextView) findViewById(R.id.about_title);
        aboutMeta = (TextView) findViewById(R.id.about_meta);
        aboutCode = (TextView) findViewById(R.id.about_code);
        qrView = (ImageView) findViewById(R.id.qr_view);
        logText = (TextView) findViewById(R.id.log_text);
        logScroll = (ScrollView) findViewById(R.id.log_scroll);
        qrCaption = (TextView) findViewById(R.id.qr_caption);
        confirmMsg = (TextView) findViewById(R.id.exit_msg);
        exitMsgView = (TextView) findViewById(R.id.exit_ing_msg);
        dlgConfirmWarn = (ImageView) findViewById(R.id.dlg_exit_warn);
        pairingCodeText = (TextView) findViewById(R.id.pairing_code);
        pairingCountdownText = (TextView) findViewById(R.id.pairing_countdown);
        pairingHintText = (TextView) findViewById(R.id.pairing_hint);

        // 主界面（照「同步到智能手机」主屏的构图）
        mainBlockDiagram = findViewById(R.id.main_block_diagram);
        mainBlockHotspot = findViewById(R.id.main_block_hotspot);
        mainBlockStatus = findViewById(R.id.status_view);
        mainIconLeft = (ImageView) findViewById(R.id.main_icon_left);
        mainIconPhone = (ImageView) findViewById(R.id.main_icon_phone);
        mainIconDots = (ImageView) findViewById(R.id.main_icon_dots);
        mainCapLeft = (TextView) findViewById(R.id.main_cap_left);
        mainCapRight = (TextView) findViewById(R.id.main_cap_right);
        btnMainLeft = (TextView) findViewById(R.id.btn_main_left);
        btnMainRight = (TextView) findViewById(R.id.btn_main_right);
        mainHotName = (TextView) findViewById(R.id.main_hot_name);
        mainHotPass = (TextView) findViewById(R.id.main_hot_pass);

        // 配对页的那一套（结构与主界面一一对应：配对码 / 热点信息 / 服务状态 + 两按钮）
        pairingBlockCode = findViewById(R.id.pairing_block_code);
        pairingBlockHotspot = findViewById(R.id.pairing_block_hotspot);
        pairingStatus = (TextView) findViewById(R.id.pairing_status);
        pairingQrView = (ImageView) findViewById(R.id.pairing_qr_view);
        pairingQrCaption = (TextView) findViewById(R.id.pairing_qr_caption);
        pairingHotName = (TextView) findViewById(R.id.pairing_hot_name);
        pairingHotPass = (TextView) findViewById(R.id.pairing_hot_pass);
        btnPairingLeft = (TextView) findViewById(R.id.btn_pairing_left);
        btnPairingRight = (TextView) findViewById(R.id.btn_pairing_right);

        // 底部按键引导 = 图标字体（原厂不是图片，是 Sony_DI_Icons 的私有区字形）
        applyIconFont(R.id.footer_key_main, R.id.footer_key_menu,
                R.id.footer_key_menu_back, R.id.footer_key_mode, R.id.footer_mode_back,
                R.id.footer_key_pairing, R.id.footer_key_pairing_menu, R.id.footer_key_about,
                R.id.footer_key_paired, R.id.footer_key_paired_menu,
                R.id.footer_key_paired_back,
                R.id.footer_key_dlg_exit, R.id.footer_key_dlg_default,
                R.id.footer_key_log);

        btnConfirmOk = (TextView) findViewById(R.id.btn_exit_ok);
        btnConfirmCancel = (TextView) findViewById(R.id.btn_exit_cancel);
        btnDefaultOk = (TextView) findViewById(R.id.btn_default_ok);
        btnDefaultNo = (TextView) findViewById(R.id.btn_default_no);

        // 确认弹窗按钮必须可聚焦，否则方向轮移不动、高亮看不见（用户反馈）
        setupDialogButton(btnConfirmOk, new FocusSink() {
            void onFocused() {
                confirmFocus = 0;
            }
        });
        setupDialogButton(btnConfirmCancel, new FocusSink() {
            void onFocused() {
                confirmFocus = 1;
            }
        });
        setupDialogButton(btnDefaultOk, new FocusSink() {
            void onFocused() {
                defFocus = 0;
            }
        });
        setupDialogButton(btnDefaultNo, new FocusSink() {
            void onFocused() {
                defFocus = 1;
            }
        });

        // 主界面常驻两按钮：左=主功能（文案随连接方式变），右=服务状态
        setupDialogButton(btnMainLeft, new FocusSink() {
            void onFocused() {
                mainFocus = 0;
            }
        });
        setupDialogButton(btnMainRight, new FocusSink() {
            void onFocused() {
                mainFocus = 1;
            }
        });
        btnMainLeft.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBlockButton(0);
            }
        });
        btnMainRight.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBlockButton(1);
            }
        });

        // 配对页的两颗按钮：与主界面**同一套语义**（左=主功能，右=服务状态），
        // 只是各记各的焦点 —— 两屏不会同时可见，但焦点是按屏恢复的。
        setupDialogButton(btnPairingLeft, new FocusSink() {
            void onFocused() {
                pairingFocus = 0;
            }
        });
        setupDialogButton(btnPairingRight, new FocusSink() {
            void onFocused() {
                pairingFocus = 1;
            }
        });
        btnPairingLeft.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBlockButton(0);
            }
        });
        btnPairingRight.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBlockButton(1);
            }
        });

        menuList = (ListView) findViewById(R.id.menu_list);
        modeList = (ListView) findViewById(R.id.mode_list);
        pairedList = (ListView) findViewById(R.id.paired_list);

        // 行布局/选中图案按菜单族分开：
        //   页码菜单 / 设置菜单 / 已配对设备 606|473 × 49 + 橙色实心条
        //     （原厂 menu_list_selector → cmn_focus_focused）
        //   列表菜单 601×82 + 一圈发光描边框（原厂 listmenu_selected_h82px）
        // 设置菜单的行带前导单选圆点，所以用 row_set49。
        // 已配对设备**用页码菜单那套 49px 行**：用户要求"和主菜单样式相同"。
        menuList.setAdapter(new RowAdapter(menuItems,
                R.layout.row_page49, R.drawable.row_focus) {
            protected int focusPos() {
                return menuFocus;
            }

            protected void setFocusPos(int pos) {
                menuFocus = pos;
            }
        });
        modeList.setAdapter(new RowAdapter(modeItems,
                R.layout.row_set49, R.drawable.row_focus) {
            protected int focusPos() {
                return modeFocus;
            }

            protected void setFocusPos(int pos) {
                modeFocus = pos;
            }

            /** 两项都是单选项（当前连接方式画实心圆点）。 */
            protected boolean radioOn(int pos) {
                return pos == 0 ? MODE_WIFI.equals(savedMode)
                        : MODE_HOTSPOT.equals(savedMode);
            }
        });
        pairedList.setAdapter(new RowAdapter(pairedItems,
                R.layout.row_page49, R.drawable.row_focus) {
            protected int focusPos() {
                return pairedFocus;
            }

            protected void setFocusPos(int pos) {
                pairedFocus = pos;
            }
        });
        menuList.setItemsCanFocus(false);
        menuList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        modeList.setItemsCanFocus(false);
        modeList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        pairedList.setItemsCanFocus(false);
        pairedList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        menuList.setOnItemSelectedListener(new FocusTracker((BaseAdapter) menuList.getAdapter()) {
            void onNewFocus(int pos) {
                ((RowAdapter) menuList.getAdapter()).setFocusPos(pos);
            }
        });
        modeList.setOnItemSelectedListener(new FocusTracker((BaseAdapter) modeList.getAdapter()) {
            void onNewFocus(int pos) {
                ((RowAdapter) modeList.getAdapter()).setFocusPos(pos);
            }
        });
        menuList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onMenuActivated(pos);
            }
        });
        modeList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onModeActivated(pos);
            }
        });
        pairedList.setOnItemSelectedListener(new FocusTracker((BaseAdapter) pairedList.getAdapter()) {
            void onNewFocus(int pos) {
                ((RowAdapter) pairedList.getAdapter()).setFocusPos(pos);
            }
        });
        pairedList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onPairedActivated(pos);
            }
        });

        btnConfirmOk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                runConfirmAction(true);
            }
        });
        btnConfirmCancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                runConfirmAction(false);
            }
        });
        btnDefaultOk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finishModeChoice(true);
            }
        });
        btnDefaultNo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finishModeChoice(false);
            }
        });

        // 电量：标准粘性广播（索尼改写过服务但对外是标准广播，真机验证可用）
        try {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable t) {
        }
        updateBattery(registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));

        // 无线电层（热点模式用）
        Context ctx = getApplicationContext();
        try {
            wifiManager = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        } catch (Throwable t) {
            wifiManager = null;
        }
        radio = RadioWrapperFactory.getInstance(ctx);

        iFilter = new IntentFilter();
        iFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        iFilter.addAction(RadioWrapper.ACTION_DIRECT_STATE_CHANGED);
        iFilter.addAction(RadioWrapper.ACTION_GROUP_CREATE_SUCCESS);
        iFilter.addAction(RadioWrapper.ACTION_GROUP_CREATE_FAILURE);
        iFilter.addAction(RadioWrapper.ACTION_STA_CONNECTED);
        iFilter.addAction(RadioWrapper.ACTION_STA_DISCONNECTED);
        receiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                try {
                    handleEvent(intent);
                } catch (Throwable t) {
                }
            }
        };

        refreshMenuValues();
        updateMainStatus();
    }

    /** 确认弹窗按钮：可聚焦 + 焦点橙字（选择高亮可见性） */
    private abstract class FocusSink {
        abstract void onFocused();
    }

    /**
     * 按钮焦点登记。
     *
     * <p>换装后**不再手动换背景**：焦点态由 XML 的 {@code btn_dialog} 选择器
     * （state_focused → btn_focus.9）驱动。旧实现手动 setBackgroundResource(row_bg_camera)
     * 会把 9-patch 覆盖成一块方角色块，按钮的圆角/描边全丢。
     */
    private void setupDialogButton(final TextView b, final FocusSink sink) {
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) sink.onFocused();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLog.i("Life", "onResume：screen=" + screen + " phase=" + phase);
        notifyAppInfo();
        jumpingToSystem = false;
        if (shuttingDown) return;
        if (isAirplaneModeOn()) {
            daFinishOnly();
            return;
        }
        if (savedMode == null) {
            // 还没选连接方式：**先弹"请选择连接方式"，确定之后才是选择界面**
            // （用户定版）。面板先立起来当浮层底下那一屏 —— 退出提示时看到的是
            // "我刚才选连接方式那一屏"，不是黑屏。
            modeFromMenu = false;
            modeFromPairing = false;
            showScreen(SCR_MODE);
            promptModeChoice();
            return;
        }
        if (!startedOnce) {
            startedOnce = true;
            if (hasPairedDevice()) {
                // XML 里默认可见的主界面**就是**第一层 —— 补上它的屏号
                // （screen 的初值是 -1 = "还没显示过任何一屏"，见那边的注释）。
                if (screen < 0) {
                    screen = SCR_MAIN;
                    baseScreen = SCR_MAIN;
                }
            } else {
                // 本机没有已配对设备：这一态的家是**配对页**，主界面根本不立
                // （showScreen 的守卫也拦得住，这里显式立起来是为了别让 XML 默认
                // 可见的主界面在提示后面露一下脸）。提示照例盖在它上面。
                enterPairingHome();
            }
            applyMode(); // 有默认方式：直接按方式启动
            // 本机没有已配对设备：**先弹"请先与一台手机配对"再进配对页**（用户定版），
            // 服务在后台起来、界面不等它。
            // ★ "先弹窗"是硬要求：只要本机没有已配对设备，进入配对界面的前一步必须是这条提示
            //   （用户原话"无论何时只要本机中没有已配对设备，进入配对界面的前一步都要进行
            //   弹窗提示配对"）。走 requestPairing() 这一个门，别处就不用各弹各的。
            if (!hasPairedDevice()) {
                promptPairingRequired();
            }
            return;
        }
        // 回到前台（例如从系统 Wi-Fi 设置跳回来）：同一屏重显，不入栈（showScreen 里判重）。
        showScreen(screen);
    }

    @Override
    protected void onPause() {
        // 官方 BaseApp.onPause 同款：先把收尾做完、最后才 super.onPause()。
        // 离开前台只有两种来路——界面里发起的退出（shuttingDown 已置位，不进这里）、
        // 自己跳系统设置（jumpingToSystem，不进这里），或直接拨了关机拨杆：
        // 系统即将断电，必须抢在被杀前同步完成关键清理。
        if (!shuttingDown && !jumpingToSystem) {
            AppLog.w("Life", "onPause：非主动退出（拨杆关机）→ 竞速收尾");
            runForcedExitCritical();
        } else {
            AppLog.i("Life", "onPause：shuttingDown=" + shuttingDown
                    + " jumpingToSystem=" + jumpingToSystem);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        AppLog.i("Life", "onDestroy");
        super.onDestroy();
        shuttingDown = true;
        removeRadioCallbacks();
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Throwable t) {
        }
        try {
            unregisterReceiver(receiver);
        } catch (Throwable t) {
        }
        stopPtpServer();
        if (MODE_HOTSPOT.equals(savedMode)) {
            // Wi-Fi 客户端模式不动用户自己的网络；热点模式按铁律归位无线电
            try {
                radio.setDirectEnabled(false);
            } catch (Throwable t) {
            }
            try {
                wifiManager.setWifiEnabled(false);
            } catch (Throwable t) {
            }
        }
        restoreAutoPowerOff();
        if (sInstance == this) sInstance = null;
    }

    // ===== 按键（scanCode 体系：方向轮=DPAD、中键=ENTER、MENU 键、转盘拨轮=DIAL_*） =====
    //
    // ★ 相机上**只有 scanCode 有意义**：这些索尼私有键的 keyCode 一律是 0，所以日志里
    //   名字取自 scanCode（keyName 吃的就是 scan），keyCode 原样带出来只为对账。

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        AppLog.i("Key", "scan=" + event.getScanCode() + "(" + keyName(event.getScanCode())
                + ") keyCode=" + keyCode + " screen=" + screen);
        if (shuttingDown) {
            return true; // 关闭/切换流程中吞掉全部按键
        }
        switch (event.getScanCode()) {
            case ScalarInput.ISV_KEY_MENU:
                onMenuKey();
                return true;
            case ScalarInput.ISV_KEY_DELETE:
                // 垃圾桶键（机身 C2）= **菜单键**（用户定版）：只有"打开选项菜单"这一个作用。
                // ★ 用户这一轮把配对页也算进来了：配对页的 **MENU 改成了"返回"**，所以
                //   "看菜单"这件事在那里只能由垃圾桶键来做（两台机器上的按键分工要一致：
                //   主界面和配对页都是"垃圾桶=选项、MENU=回上一级"）。
                // 有取消键的确认弹窗里它算"其它键" → 直接退出弹窗（用户定版那条规矩）
                if (exitDialogByStrayKey()) return true;
                if (screen == SCR_MAIN) {
                    menuFocus = 0;
                    menuFromPairing = false;   // 来路=主界面
                    showScreen(SCR_MENU);
                } else if (screen == SCR_PAIRING) {
                    menuFocus = 0;
                    menuFromPairing = true;    // 来路=配对页（选完连接方式要回配对页）
                    showScreen(SCR_MENU);
                }
                return true;
            case ScalarInput.ISV_KEY_ENTER:
                onEnterKey();
                return true;
            case ScalarInput.ISV_KEY_UP:
            case ScalarInput.ISV_KEY_DOWN:
                // ★ 上下键**只驱动竖排的东西**（列表、上下排列的弹窗按钮对、可滚动的正文）。
                //   主界面那两颗按钮是横排的，这里不许动它 —— 用户定版：
                //   "上下排列的按钮只能通过上下键切换，左右排列的按钮只能通过左右键切换"。
                step(event.getScanCode() == ScalarInput.ISV_KEY_UP ? STEP_UP : STEP_DOWN);
                return true;
            case ScalarInput.ISV_KEY_LEFT:
                // ★ 左右键**只驱动横排的东西**（当前只有主界面那两颗按钮）。
                //   确认弹窗的两颗按钮是**竖排**的 → 左右键不是"移动"，而是"其它键"：
                //   按用户定版直接退出弹窗（见 exitDialogByStrayKey）。
                if (exitDialogByStrayKey()) return true;
                step(STEP_LEFT);
                return true;
            case ScalarInput.ISV_KEY_RIGHT:
                if (exitDialogByStrayKey()) return true;
                step(STEP_RIGHT);
                return true;
            // ===== 转盘拨轮（全局）=====
            // 转动一格 = 沿"当前屏的排列方向"走一步：竖排屏等同上下键，横排屏等同左右键。
            // 值与出处见 ScalarInput 桩的类注释（从固件 dex 里读出来的真值）。
            case ScalarInput.ISV_DIAL_KURU_CLOCKWISE:
            case ScalarInput.ISV_DIAL_1_CLOCKWISE:
            case ScalarInput.ISV_DIAL_2_CLOCKWISE:
            case ScalarInput.ISV_DIAL_3_CLOCKWISE:
                return onDial(true);
            case ScalarInput.ISV_DIAL_KURU_COUNTERCW:
            case ScalarInput.ISV_DIAL_1_COUNTERCW:
            case ScalarInput.ISV_DIAL_2_COUNTERCW:
            case ScalarInput.ISV_DIAL_3_COUNTERCW:
                return onDial(false);
            // 转盘的"存在/状态"广播：不是转动事件，收下不动（更不能当成"其它键"去关弹窗）
            case ScalarInput.ISV_DIAL_KURU_STATUS:
            case ScalarInput.ISV_DIAL_1_STATUS:
            case ScalarInput.ISV_DIAL_2_STATUS:
            case ScalarInput.ISV_DIAL_3_STATUS:
                return true;
            default:
                // ★ 用户定版：有"取消"的弹窗（单按钮通知不算）里，**上下键以外任何键都直接退出**
                //   —— 机身上一堆键（Fn/DISP/删除/自定义/S1…）在弹窗里本来就没用，按下去
                //   什么都不发生会让人以为死机；退出弹窗是最不伤人的解释。
                //   功能设置询问框（DLG_DEFAULT）**按用户要求除外**，它不吞键也不退。
                if (exitDialogByStrayKey()) return true;
                return super.onKeyDown(keyCode, event);
        }
    }

    /**
     * "其它键 = 退出弹窗"那条规矩（用户定版）的实现，返回 true 表示这次按键已被消费。
     *
     * <p>适用对象是**有取消键的确认弹窗**（{@code DLG_CONFIRM} 且不是单按钮通知态）；
     * 功能设置询问框 {@code DLG_DEFAULT} 按用户要求排除在外。
     * 退出 = 走"取消"那条路（回弹窗下面那一屏），不是执行确定。
     */
    private boolean exitDialogByStrayKey() {
        if (screen != DLG_CONFIRM || noticeMode) {
            return false;
        }
        AppLog.i("UI", "确认弹窗：非上下键 → 直接退出（取消）");
        closeDialog();
        return true;
    }

    /** 转盘拨轮：顺时针 = 下一步，逆时针 = 上一步（方向由当前屏的排列决定）。 */
    private boolean onDial(boolean clockwise) {
        // 横排的是"两颗按钮"的屏：主界面与配对页（两者正文块都是横排按钮行）
        boolean horizontal = (screen == SCR_MAIN || screen == SCR_PAIRING);
        AppLog.i("Key", "拨轮" + (clockwise ? "顺时针" : "逆时针")
                + " → " + (horizontal ? (clockwise ? "右" : "左") : (clockwise ? "下" : "上"))
                + "（screen=" + screenName(screen) + "）");
        if (horizontal) {
            step(clockwise ? STEP_RIGHT : STEP_LEFT);
        } else {
            step(clockwise ? STEP_DOWN : STEP_UP);
        }
        return true;
    }

    /**
     * MENU 键（机身 MENU，与垃圾桶键分工不同）。
     *
     * <p>★ 铁律（用户定版）：**MENU = 返回上一层级，也就是上一次显示的那个界面**；
     * 已经处于第一层、没有可返回的界面时，MENU = 退出软件。
     *
     * <p>实现只有两条路：
     * <ul>
     *   <li>浮层（确认框/通知框）**不算一层独立的去处**：MENU 在这里只做"关掉它"，
     *       回到它压着的那一屏（{@link #baseScreen}），**不执行浮层上的动作** ——
     *       那是确定键的事。关之前记得把通知借用的面板归位（见 resetNoticePanel）。</li>
     *   <li>其余屏一律 {@link #goBackOneLevel()}：弹 navHistory 的栈顶。
     *       栈空 = 这里是第一层 → 弹退出确认。</li>
     * </ul>
     *
     * <p>换装那版是一屏一段手写分支（主界面退出、菜单回主界面、关于回配对页…），
     * 每加一屏都要替它想一遍"上一级是谁"；现在屏与"来路"彻底解耦 —— 加屏不用改这里，
     * 只要它经 {@link #showScreen} 显示。
     */
    private void onMenuKey() {
        switch (screen) {
            case DLG_CONFIRM:
                // 单按钮通知借的是确认面板：关掉之前先把它恢复成双按钮形态，
                // 否则面板会留在"藏了取消、确定挪到 336"的形态里，下一次真确认就少一个取消键。
                if (noticeMode) {
                    resetNoticePanel();
                }
                closeDialog();
                break;
            case DLG_DEFAULT:
                // "设为默认"询问框：上一级是它压着的那个面板
                closeDialog();
                break;
            case DLG_EXITING:
                // 退出/切换浮层期间按键本就被 onKeyDown 的 shuttingDown 拦掉，这里不做事
                break;
            default:
                if (!goBackOneLevel()) {
                    // 第一层：没有可返回的界面 → MENU = 退出软件（弹确认）
                    showConfirm(CONFIRM_EXIT);
                }
                break;
        }
    }

    /**
     * MENU 的"返回上一层级"：弹栈，落到上一次显示的那个界面。
     *
     * @return false 表示**已经处于第一层**（历史为空），调用方按"退出软件"处理。
     *
     * <p>这里**只弹栈**，不做任何"这一屏能不能去"的判断 —— 那两条铁律守卫
     * （无已配对设备时主界面不立、进配对页必经配对提示）都在 {@link #showScreen} 里，
     * 落点由它兜住：比如弹出来的落点是主界面而本机没有已配对设备，会被改成
     * 配对流程（先弹提示）。这样"从哪儿返回"与"能不能去"永远是同一份规则。
     */
    private boolean goBackOneLevel() {
        if (navHistory.isEmpty()) {
            AppLog.i("Key", "MENU：已在第一层（无上一级）→ 退出软件");
            return false;
        }
        int target = navHistory.remove(navHistory.size() - 1);
        AppLog.i("Key", "MENU 返回上一级 → " + screenName(target));
        showScreen(target, true);
        return true;
    }

    /** 屏号 → 名字（日志里比数字好认）。 */
    private static String screenName(int sc) {
        switch (sc) {
            case SCR_MAIN: return "主界面";
            case SCR_MENU: return "选项菜单";
            case SCR_MODE: return "连接设置";
            case SCR_ABOUT: return "关于";
            case DLG_CONFIRM: return "确认弹窗";
            case DLG_DEFAULT: return "设为默认弹窗";
            case DLG_EXITING: return "退出/切换浮层";
            case SCR_PAIRING: return "配对页";
            case SCR_PAIRED: return "已配对设备";
            case SCR_LOG: return "调试日志";
            default: return String.valueOf(sc);
        }
    }

    /** scanCode → 人看的名字（日志里比数字好认；相机上 keyCode 恒 0，认键只能靠 scan）。 */
    private static String keyName(int scan) {
        switch (scan) {
            case ScalarInput.ISV_KEY_UP: return "UP";
            case ScalarInput.ISV_KEY_DOWN: return "DOWN";
            case ScalarInput.ISV_KEY_LEFT: return "LEFT";
            case ScalarInput.ISV_KEY_RIGHT: return "RIGHT";
            case ScalarInput.ISV_KEY_ENTER: return "ENTER";
            case ScalarInput.ISV_KEY_MENU: return "MENU";
            case ScalarInput.ISV_KEY_DELETE: return "DELETE(垃圾桶)";
            case ScalarInput.ISV_KEY_SK1: return "SK1";
            case ScalarInput.ISV_KEY_SK2: return "SK2";
            case ScalarInput.ISV_KEY_PLAY: return "PLAY";
            // 转盘拨轮：日志屏里转一下就能看出机身这个转盘到底报的是哪一个
            case ScalarInput.ISV_DIAL_KURU_CLOCKWISE: return "拨轮KURU顺时针";
            case ScalarInput.ISV_DIAL_KURU_COUNTERCW: return "拨轮KURU逆时针";
            case ScalarInput.ISV_DIAL_1_CLOCKWISE: return "拨轮1顺时针";
            case ScalarInput.ISV_DIAL_1_COUNTERCW: return "拨轮1逆时针";
            case ScalarInput.ISV_DIAL_2_CLOCKWISE: return "拨轮2顺时针";
            case ScalarInput.ISV_DIAL_2_COUNTERCW: return "拨轮2逆时针";
            case ScalarInput.ISV_DIAL_3_CLOCKWISE: return "拨轮3顺时针";
            case ScalarInput.ISV_DIAL_3_COUNTERCW: return "拨轮3逆时针";
            case ScalarInput.ISV_DIAL_KURU_STATUS: return "拨轮KURU状态";
            case ScalarInput.ISV_DIAL_1_STATUS: return "拨轮1状态";
            case ScalarInput.ISV_DIAL_2_STATUS: return "拨轮2状态";
            case ScalarInput.ISV_DIAL_3_STATUS: return "拨轮3状态";
            // 其余键（不参与界面逻辑，只为日志里认得出）
            case ScalarInput.ISV_KEY_STASTOP: return "STASTOP(录像)";
            case ScalarInput.ISV_KEY_S1_1: return "S1_1(半按)";
            case ScalarInput.ISV_KEY_S1_2: return "S1_2";
            case ScalarInput.ISV_KEY_S2: return "S2";
            case ScalarInput.ISV_KEY_FN: return "FN";
            case ScalarInput.ISV_KEY_DISP: return "DISP";
            case ScalarInput.ISV_KEY_AEL: return "AEL";
            case ScalarInput.ISV_KEY_CUSTOM1: return "C1";
            case ScalarInput.ISV_KEY_CUSTOM2: return "C2(自定义)";
            case ScalarInput.ISV_KEY_CUSTOM3: return "C3";
            case ScalarInput.ISV_KEY_MODE_DIAL: return "模式转盘";
            case ScalarInput.ISV_KEY_EV_COMPENSATION: return "曝光补偿转盘";
            case ScalarInput.ISV_KEY_IRIS_DIAL: return "光圈转盘";
            case ScalarInput.ISV_KEY_UNKNOWN: return "UNKNOWN";
            case ScalarInput.KEY_NOT_SUPPORTED: return "NOT_SUPPORTED";
            default: return "0x" + Integer.toHexString(scan);
        }
    }

    private void onEnterKey() {
        switch (screen) {
            case SCR_MAIN:
                if (phase == PH_FAILED) {
                    retryFromFailure();
                } else {
                    // 主界面常驻两按钮：ENTER 触发当前焦点的那个
                    onBlockButton(mainFocus);
                }
                break;
            case SCR_MENU:
                onMenuActivated(menuFocus);
                break;
            case SCR_MODE:
                onModeActivated(modeFocus);
                break;
            case SCR_PAIRING:
                // 配对页也有常驻两按钮（与主界面同一套）：ENTER 触发当前焦点的那个。
                // 配对码本身不可操作 —— 它是给手机看的，相机这边按它没有意义。
                onBlockButton(pairingFocus);
                break;
            case SCR_PAIRED:
                onPairedActivated(pairedFocus);
                break;
            case SCR_ABOUT:
                // **隐藏入口**：关于页连续按十下确定键 → 调试日志屏（正常用不会误触）。
                // "连续"= 两次间隔不超过 ABOUT_ENTER_WINDOW_MS，超了从头数；
                // 离开关于页（showScreen 进关于分支）也会清零。
                long nowUp = SystemClock.elapsedRealtime();
                if (nowUp - aboutEnterLast > ABOUT_ENTER_WINDOW_MS) {
                    aboutEnterCount = 0;
                }
                aboutEnterLast = nowUp;
                aboutEnterCount++;
                if (aboutEnterCount >= ABOUT_ENTER_COUNT) {
                    aboutEnterCount = 0;
                    AppLog.i("UI", "隐藏入口：连按 " + ABOUT_ENTER_COUNT + " 下确定 → 调试日志屏");
                    showScreen(SCR_LOG);
                }
                break;
            case SCR_LOG:
                // 日志屏：确定键 = 重新读一遍（手动刷新），MENU 回关于页
                AppLog.i("Key", "日志屏：确定键刷新");
                updateLogScreen();
                break;
            case DLG_CONFIRM:
                runConfirmAction(confirmFocus == 0);
                break;
            case DLG_DEFAULT:
                finishModeChoice(defFocus == 0);
                break;
            default:
                break;
        }
    }

    private void retryFromFailure() {
        if (MODE_HOTSPOT.equals(savedMode)) {
            initAndStartWifiCycle(true);
        } else {
            startStationFlow();
        }
    }

    // ===== 焦点移动 / 翻页的"一步" =====
    //
    // ★ 用户定的两条规矩（本轮）：
    //   ① **上下键与左右键的语义不许混**：竖排的东西只认上下键，横排的只认左右键。
    //      本工程里只有主界面那两颗按钮是横排，其余（三个列表、两个弹窗的按钮对）全是竖排。
    //   ② **转盘拨轮全局可用**：转动一格 = 沿"当前屏的排列方向"走一步 ——
    //      竖排屏等同于按上下键，横排屏等同于按左右键（见 onDial）。
    //   所以移动逻辑只此一份（step），按键与拨轮都往这里汇，语义不会各自长歪。

    private static final int STEP_UP = 0;
    private static final int STEP_DOWN = 1;
    private static final int STEP_LEFT = 2;
    private static final int STEP_RIGHT = 3;

    /**
     * 沿 {@code dir} 方向走一步。**按"当前屏怎么排的"来判**，不看按的是哪个键 ——
     * 上下键传 STEP_UP/DOWN、左右键传 STEP_LEFT/RIGHT，走不通的方向就什么都不做
     * （比如主界面的上下键、列表的左右键）。
     */
    private void step(int dir) {
        switch (screen) {
            case SCR_MAIN:
                // 两颗按钮**横排** → 只认左右键（用户定版：上下键在这里不许动焦点）
                if (dir == STEP_LEFT) btnMainLeft.requestFocus();
                else if (dir == STEP_RIGHT) btnMainRight.requestFocus();
                break;
            case SCR_PAIRING:
                // 配对页也是**两颗横排按钮**（与主界面同款）→ 同样只认左右键。
                // 正文第 0 块（配对码）没有可聚焦的东西，左右键先落在按钮上是对的。
                if (dir == STEP_LEFT) btnPairingLeft.requestFocus();
                else if (dir == STEP_RIGHT) btnPairingRight.requestFocus();
                break;
            case SCR_MENU:
                // 竖排列表 → 只认上下键。行数用**适配器当前的行数**，不写死 menuItems.length：
                // 越界时 RowAdapter.getView() 会 items[i] 崩掉。
                if (dir == STEP_UP || dir == STEP_DOWN) {
                    moveListFocus(menuList, menuList.getAdapter().getCount(),
                            dir == STEP_UP ? -1 : 1);
                }
                break;
            case SCR_MODE:
                if (dir == STEP_UP || dir == STEP_DOWN) {
                    moveListFocus(modeList, modeItems.length, dir == STEP_UP ? -1 : 1);
                }
                break;
            case SCR_PAIRED:
                if (dir == STEP_UP || dir == STEP_DOWN) {
                    moveListFocus(pairedList, pairedList.getAdapter().getCount(),
                            dir == STEP_UP ? -1 : 1);
                }
                break;
            case DLG_CONFIRM:
                // 弹窗两颗按钮是**竖排**（确定在上、取消在下，原厂坐标 274/340 就摆在那儿）
                // → 只认上下键。单按钮通知态没有第二颗可去，上下键也不动。
                if (!noticeMode && (dir == STEP_UP || dir == STEP_DOWN)) {
                    (confirmFocus == 0 ? btnConfirmCancel : btnConfirmOk).requestFocus();
                }
                break;
            case DLG_DEFAULT:
                if (dir == STEP_UP || dir == STEP_DOWN) {
                    (defFocus == 0 ? btnDefaultNo : btnDefaultOk).requestFocus();
                }
                break;
            case SCR_LOG:
                // 日志屏没有可聚焦的行，走的方向就是"翻正文"（一屏铺 300 行，不翻只看得到尾巴）
                if (dir == STEP_UP) scrollLog(-1);
                else if (dir == STEP_DOWN) scrollLog(1);
                break;
            default:
                // 配对页/关于页没有可动的东西：按键与拨轮到此为止，不做任何事
                break;
        }
    }

    /** 日志屏一次翻几行（26px 行高 × 3）。 */
    private static final int LOG_SCROLL_STEP = 78;

    private void scrollLog(int dir) {
        if (logScroll == null) {
            return;
        }
        logScroll.smoothScrollBy(0, dir * LOG_SCROLL_STEP);
    }

    private void moveListFocus(ListView list, int count, int delta) {
        RowAdapter adapter = (RowAdapter) list.getAdapter();
        if (count <= 0) return;
        int next = (adapter.focusPos() + delta + count) % count;
        adapter.setFocusPos(next);
        list.setSelection(next);
        adapter.notifyDataSetChanged();
    }

    // ===== 屏幕切换 =====

    private void showScreen(int target) {
        showScreen(target, false);
    }

    /**
     * 切屏。**这里是"层级历史"（navHistory）唯一的记帐点** —— 每次真的换了屏，
     * 顺手把"从哪一跳来的"记下来，MENU 才有"上一次显示的那个界面"可回。
     *
     * @param back true = 这次跳转本身就是 **MENU 的返回**（落点已由 goBackOneLevel 从栈里弹出）：
     *             不再入栈，否则返回会把自己又记成一层、MENU 变成原地打转。
     */
    private void showScreen(int target, boolean back) {
        if (target < 0) {
            // 兜底：屏号非法（理论上是"浮层先于任何基础屏出现"那种不可能的情形）。
            // 一屏都不显示会是**黑屏且出不去**，比落到主界面危险得多。
            AppLog.w("UI", "showScreen 收到非法屏号 " + target + " → 落主界面");
            target = SCR_MAIN;
        }
        // ===== 两条铁律守卫（**只在这里判一次**）=====
        //
        // 这两条以前是散在各个调用点上的（landAfterModeChoice 一处、beginModeSwitch 的
        // 完成回调一处、goBackOneLevel 一处…），加一条新路径就得记着再判一次 ——
        // 用户的原话是"只把'此界面此操作应该干什么'写进程序是不够的，如果用户做出了
        // 你没有预料到的操作呢"。所以改成**在唯一的切屏口上兜住**：任何调用方、
        // 任何按键路径、以后新加的任何功能，都绕不过去。
        //
        // ① **本机没有已配对设备时，主界面根本不存在**。主界面画的是「相机 ↔ 手机」
        //    这一对，一台手机都没配过时它没有对象；用户定版原话："一个已经配对的设备
        //    都没有时，严禁进入正常状态下的主页面"。所以目标改成配对页 —— 那一态的家。
        //
        //    ★ 先记下"调用方**原本**想去哪一屏"，守卫②要用它判断这次算不算"刚从别处
        //      掉进无设备状态"（改写过的 target 会把这个信息抹掉）。
        final int requested = target;
        if (target == SCR_MAIN && !hasPairedDevice()) {
            AppLog.w("UI", "无已配对设备：主界面不立 → 改走配对流程");
            target = SCR_PAIRING;
        }
        // ② **没有已配对设备时，"进配对页"的前一步必须是"请先与一台手机配对"**。
        //    提示的用途是**在掉进"这台机器还没配对过手机"这个状态时告诉用户一声**，
        //    所以"用户已经在配对流程里"的几种情形不再拦：
        //      · enteringPairingHome —— 应用把这一屏**立成家**（提示由调用方马上盖上去）；
        //      · 配对页本来就是当前这一屏（关掉盖在它上面的浮层 = 回到它，不是进它）；
        //      · **配对页是用户自己走到过的地方**（{@link #pairingFlowVisited}）——
        //        从它的菜单/关于绕一圈回来，或者 MENU 从它走出来再返回，他显然知道自己在配对，
        //        再拦一道只是噪声；
        //    与之相对，**"刚把最后一台手机解除绑定"这种从别处掉进这一态的必须提示**
        //    （那时用户没进过配对页，层级历史里也没有它）。
        if (target == SCR_PAIRING && !hasPairedDevice()
                && !enteringPairingHome && !pairingPageIsCurrent()
                && !pairingFlowVisited(requested, back)) {
            AppLog.w("UI", "无已配对设备：进配对页前先弹配对提示（用户还没进过配对流程）");
            promptPairingRequired();
            return;          // 本次切屏作废（屏号都不动）：提示的确定会再走一次这里
        }
        int prevForLog = screen;
        int prev = screen;
        recordHistory(prev, target, back);
        // 无已配对设备时的配对页 = 这台机器的**家**：层级历史清零（用户定版：
        // 这种"应用自动把你放进来的"配对页，MENU 就是退出软件）。用户自己从菜单
        // 点进来那一支本机有设备，历史照常留着 → MENU 就是"返回刚才那一屏"。
        if (target == SCR_PAIRING && !hasPairedDevice()) {
            navHistory.clear();
        }
        screen = target;
        handler.removeCallbacks(pairedTicker);   // 只有停在设备列表页时才需要它跑
        // 离开配对页 = 停止对外可配对（铁律式收尾：离开模式必须关窗）。
        // ★ 挂在 showScreen 上而不是各个按键分支上：配对页能绕出去的路不止一条
        //   （MENU 返回上一级；垃圾桶键 → 选项菜单 → 选连接方式/看关于 → 再回主界面），
        //   只堵在"离开配对页"那一两个按键分支上会漏 —— 兜别处一圈回主界面时配对窗
        //   还开着，手机照样能配进来。closeWindow() 自身幂等，重复调用无害。
        // 离开配对页 = 停止对外可配对（铁律式收尾：离开模式必须关窗）。
        // ★ 挂在 showScreen 上而不是各个按键分支上：配对页能绕出去的路不止一条
        //   （MENU 返回上一级；垃圾桶键 → 选项菜单 → 选连接方式/看关于 → 再回主界面），
        //   只堵在"离开配对页"那一两个按键分支上会漏 —— 兜别处一圈回主界面时配对窗
        //   还开着，手机照样能配进来。closeWindow() 自身幂等，重复调用无害。
        // ★ **浮层不算"离开"**（用户指出"换码"这件事该由功能自己说清）：浮层只是盖在
        //   配对页上，底下那一屏还是配对页 —— 关掉浮层回来时那张码必须还在。原来的写法
        //   是"目标屏号不是配对页就关窗"，于是"配对页上弹一条提示、关掉"就白白换了一张码
        //   （启动时那条配对提示、以及任何弹在配对页上的框都会这样），用户会看到码自己变了。
        //   "配对码连错 5 次要换新码"改由那条提示**显式**换（见 onPairingAttemptFailed）——
        //   功能该在哪儿发生就写在哪儿，不靠副作用。
        AppLog.i("UI", "切屏 " + screenName(prevForLog) + " → " + screenName(target)
                + "（MENU 上一级 " + historyText() + "）");
        if (prev == SCR_PAIRING && target != SCR_PAIRING && !isDialogScreen(target)) {
            leavePairingMode();
        }
        // 浮层语义（原厂就是这么挂的）：连接设置弹窗与三个对话框都是**悬浮在下层之上** ——
        // 原厂 menu_set.xml 的根就是一层 #88000000 遮罩 + 一块面板；对话框是
        // cmn_layout_dialog_*（遮罩 #66000000）+ 面板。下层那一屏**不收起来**，
        // 透过遮罩能看见（用户要的"后面不是黑屏、能露出后面的东西"）。
        // 所以浮层目标一个 base 屏都不动，只切自己的可见性；非浮层目标照旧互斥显示。
        boolean isDialog = (target == DLG_CONFIRM || target == DLG_DEFAULT
                || target == DLG_EXITING);
        if (target == SCR_MODE) {
            screenMode.setVisibility(View.VISIBLE);
        } else if (!isDialog) {
            screenMain.setVisibility(target == SCR_MAIN ? View.VISIBLE : View.GONE);
            screenMenu.setVisibility(target == SCR_MENU ? View.VISIBLE : View.GONE);
            screenMode.setVisibility(View.GONE);
            screenAbout.setVisibility(target == SCR_ABOUT ? View.VISIBLE : View.GONE);
            screenLog.setVisibility(target == SCR_LOG ? View.VISIBLE : View.GONE);
            screenPairing.setVisibility(target == SCR_PAIRING ? View.VISIBLE : View.GONE);
            screenPaired.setVisibility(target == SCR_PAIRED ? View.VISIBLE : View.GONE);
        }
        dlgConfirm.setVisibility(target == DLG_CONFIRM ? View.VISIBLE : View.GONE);
        dlgDefault.setVisibility(target == DLG_DEFAULT ? View.VISIBLE : View.GONE);
        dlgExiting.setVisibility(target == DLG_EXITING ? View.VISIBLE : View.GONE);

        if (target == SCR_MAIN) {
            // 主界面有了常驻按钮行：进屏即把焦点放到左键，并把文案/正文块刷成当前状态
            mainFocus = 0;
            refreshScreens();
            btnMainLeft.requestFocus();
        } else if (target == SCR_MENU) {
            // 菜单**按来路**取模型：从配对页按垃圾桶进来的那一份藏着"进入配对模式"
            // （已经在配对模式里了），其余一字不差（见 activeMenu）。
            RowAdapter ma = (RowAdapter) menuList.getAdapter();
            ma.setItems(activeMenu());
            if (menuFocus >= ma.getCount()) {
                menuFocus = ma.getCount() - 1;
            }
            refreshMenuValues();   // 配对台数/连接方式每次进菜单都刷新
            ma.notifyDataSetChanged();
            menuList.requestFocus();
            menuList.setSelection(ma.focusPos());
            ma.notifyDataSetChanged();
        } else if (target == SCR_MODE) {
            modeList.requestFocus();
            modeList.setSelection(((RowAdapter) modeList.getAdapter()).focusPos());
            ((BaseAdapter) modeList.getAdapter()).notifyDataSetChanged();
        } else if (target == SCR_ABOUT) {
            // 关于页是静态三行（用户定版），没有滚动也没有异步读取。
            // 只有"本机设备码"是运行期值，进屏时取一次（它随机生成一次后就不再变）。
            updateAboutText();
            aboutEnterCount = 0;      // 每次进关于页，连按计数从头数
        } else if (target == SCR_LOG) {
            updateLogScreen();
        } else if (target == SCR_PAIRING) {
            // 正文块与焦点都归位到第 0 块 / 键一 ——"回到原来那一页"不算新进
            // （例如从系统 Wi-Fi 设置跳回来时，不该把用户正看的服务状态掐掉）。
            if (prev != SCR_PAIRING) {
                pairingBlock = 0;
                pairingFocus = 0;
            }
            // 先开窗再刷：状态行那句"（配对模式）"的判据就是**窗口开着**
            // （见 inPairingMode），顺序反了这一帧的状态文本会少一个括号。
            enterPairingMode();
            refreshScreens();
            if (pairingFocus == 0) {
                btnPairingLeft.requestFocus();
            } else {
                btnPairingRight.requestFocus();
            }
        } else if (target == SCR_PAIRED) {
            rebuildPairedItems();
            pairedList.requestFocus();
            pairedList.setSelection(pairedFocus);
            ((BaseAdapter) pairedList.getAdapter()).notifyDataSetChanged();
            handler.removeCallbacks(pairedTicker);
            handler.postDelayed(pairedTicker, 1000);
        } else if (target == DLG_CONFIRM) {
            // 感叹号图标的显隐：双按钮确认弹窗恒显示（原厂 `..._string_with_icon_two_button...`
            // 就是"带图标"那款，用户点名要的）；单按钮通知里只有警告性质的给图标。
            dlgConfirmWarn.setVisibility(
                    !noticeMode || noticeWarn ? View.VISIBLE : View.GONE);
            confirmFocus = 0;
            btnConfirmOk.requestFocus();
        } else if (target == DLG_DEFAULT) {
            defFocus = 0;
            btnDefaultOk.requestFocus();
        } else if (target == DLG_EXITING) {
            // ★ 全屏"正在退出/正在切换模式"浮层期间**吞掉一切按键**（用户定版）：
            //   光有 onKeyDown 里的 shuttingDown 总闸还不够 —— 按键先派给**焦点所在
            //   的 View**，浮层出现时不收焦点，焦点就还留在底下那屏的按钮/列表上：
            //   ENTER 会点到底下的按钮、方向键会挪列表焦点，Activity 根本收不到，
            //   表现就是"按一下任意键，浮层没了/底下动了"。把焦点收进浮层自己
            //   （一个不可点击的普通容器，它不消费任何键）→ 事件落回 Activity
            //   → 总闸统一吞掉。浮层关闭走 showScreen，落点屏各自 requestFocus，无需恢复。
            dlgExiting.setFocusable(true);
            dlgExiting.setFocusableInTouchMode(true);
            dlgExiting.requestFocus();
        }
        // 顶栏 Wi-Fi 指示跟着当前帧一起刷：退出/切换模式的浮层一上来就该灭掉
        // （那时无线电正在拆，"Wi-Fi 已就绪"不再是实话）。稳态变化由 1s 心跳兜住。
        refreshWifiIcon();
        // 底栏"MENU 后面那块"也跟着一起刷：**提示与 MENU 的实际行为同源**
        // （见 refreshMenuHint —— 功能变了提示必须跟着变，否则就是假话）。
        refreshMenuHint();
    }

    /**
     * 刷底栏里"MENU 后面那块"：要么返回箭头、要么"退出"两个字。
     *
     * <p>用户定版：**MENU 的功能不同时，下方提示栏的提示也要随之变化**。
     * 图标字体的私有区里没有汉字，所以这类底栏都备了**两个控件**（箭头 / 退出），
     * 这里统一切可见性 —— 判据只有一条，而且与 MENU 的实际行为同源：
     * **层级历史空 = 这一屏就是第一层 = MENU 退出**（goBackOneLevel 弹空栈即退出软件）。
     *
     * <p>为什么按 {@code navHistory.isEmpty()} 而不是"当前屏"算：底栏长在**基础屏**上，
     * 浮层只是盖在它上面（浮层不收下层，透过遮罩看得见）—— 浮层出现时下层那一屏在层级里
     * 是第几层并没有变，MENU 在浮层上只做"关掉浮层"，落回下层之后才轮到下层那一步。
     * 浮层自己的底栏是静态的返回箭头（MENU 恒为"关掉它"），所以不在这里刷。
     *
     * <p>哪些屏会走到"退出"：首启的连接设置面板（它就是第一层）、无已配对设备时的
     * 配对页（那一态的家 → 历史被清空）、主界面（恒为第一层，底栏是静态的"退出"）。
     * 其余屏（菜单/已配对设备/关于/日志）**永远有上一级**：它们只能从下层那一屏进去，
     * 所以底栏是静态的返回箭头 —— 这不是"漏了两种可能"，而是这些屏的存在方式决定的。
     */
    private void refreshMenuHint() {
        boolean exit = navHistory.isEmpty();
        setMenuHint(R.id.footer_mode_back, R.id.footer_mode_exit, exit);
        setMenuHint(R.id.footer_pairing_back, R.id.footer_pairing_exit, exit);
    }

    private void setMenuHint(int backId, int exitId, boolean exit) {
        View back = findViewById(backId);
        if (back != null) {
            back.setVisibility(exit ? View.GONE : View.VISIBLE);
        }
        View ex = findViewById(exitId);
        if (ex != null) {
            ex.setVisibility(exit ? View.VISIBLE : View.GONE);
        }
    }

    /** 浮层（悬浮在下层之上，下层不收起来）：它们不参与"层级历史"，也不改 baseScreen。 */
    private static boolean isDialogScreen(int sc) {
        return sc == DLG_CONFIRM || sc == DLG_DEFAULT || sc == DLG_EXITING;
    }

    /**
     * 关掉浮层：回到它压着的那一屏。
     *
     * <p>底下没有屏（{@code baseScreen < 0}）说明"关掉它"之后无处可去 —— 那它自己就是
     * 第一层，MENU 只能是退出软件。正常路径都满足"浮层出现前先立基础屏"（首启的连接
     * 设置面板、启动时的配对页都是先立屏再把提示盖上去），这里只为万一留一条明路：
     * 相机上排障只能靠日志屏，静默兜底等于把问题藏起来。
     */
    private void closeDialog() {
        if (baseScreen < 0) {
            AppLog.w("UI", "关浮层时底下没有基础屏 → 按第一层处理（退出确认）");
            showConfirm(CONFIRM_EXIT);
            return;
        }
        showScreen(baseScreen);
    }

    /**
     * 层级历史记帐（只由 {@link #showScreen} 调）。
     *
     * <p>规则四条，都是从"MENU = 上一次显示的那个界面"这一条推出来的：
     * <ol>
     *   <li>只记**基础屏**：浮层不算一层去处 —— 它底下那一屏才是"上一次显示的界面"
     *       （所以从浮层跳到基础屏时，入栈的是 baseScreen，不是浮层自己）；</li>
     *   <li>**落到主界面 = 回到第一层**：历史直接清空（主界面没有上一级，MENU 在这儿
     *       就是退出软件 —— 用户定版的铁律，不能因为"刚才从菜单绕进来"就变成回菜单）；</li>
     *   <li>**关掉浮层（落点就是它压着的那一屏）不入栈**：那是"取消/关闭"，不是往前走；</li>
     *   <li>**同一屏重复入栈没有意义**（A→B→A→B 这种），已经在栈里的屏不再入栈 ——
     *       否则从配对页绕一圈回配对页会在栈里压出两个配对页，MENU 第一下先"回到自己"，
     *       配对窗被重开、码也换了。</li>
     * </ol>
     * 同屏重显（回前台时的 {@code showScreen(screen)}）不入栈。
     */
    private void recordHistory(int prev, int target, boolean back) {
        if (isDialogScreen(target)) {
            return;                       // 浮层：只显示，不记帐
        }
        if (target == SCR_MAIN) {
            navHistory.clear();           // 主界面 = 第一层
            baseScreen = SCR_MAIN;
            return;
        }
        boolean closingDialog = isDialogScreen(prev) && target == baseScreen;
        if (!back && !closingDialog && prev != target) {
            int from = isDialogScreen(prev) ? baseScreen : prev;
            if (from >= 0 && from != target && !navHistory.contains(from)) {
                if (navHistory.size() >= NAV_HISTORY_MAX) {
                    navHistory.remove(0);   // 丢最旧的：最近几层才是 MENU 要用的
                }
                navHistory.add(from);
            }
        }
        baseScreen = target;
    }

    /** 历史快照（日志用；相机上出问题只能靠日志复现"上一级是谁"）。 */
    private String historyText() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < navHistory.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append(screenName(navHistory.get(i)));
        }
        return sb.append(']').toString();
    }

    /** 通用确认弹窗（用户反馈：确定/取消看不出当前选择——按钮已可聚焦+橙字高亮） */
    private void showConfirm(int what) {
        // ★ 结构性保障：上一个弹窗若是**单按钮通知**、又被 MENU 直接关掉（那条路走
        //   showScreen(baseScreen)，没经过 finishNotice），面板会留在"藏了取消、
        //   确定挪到 336"的形态 —— 这时弹出来的确认框会**少一个取消键**，而且
        //   runConfirmAction 会先去执行那条通知的动作（比如"确定"变去配对页而不是退出）。
        //   所以进任何确认框之前无条件把面板恢复成双按钮形态。
        resetNoticePanel();
        pendingConfirm = what;
        if (what == CONFIRM_EXIT) {
            confirmMsg.setText("确定要退出 SonyConnect 吗？");
        } else if (what == CONFIRM_UNPAIR) {
            // 文案已在 onPairedActivated 里带上对端名字
        } else {
            // 进配对模式会**主动断开当前连着的手机**（见 runConfirmAction），
            // 这话必须写进弹窗里，否则用户以为只是换个页、手机还连着。
            confirmMsg.setText("进入配对模式？\n此操作会断开与手机端的连接");
        }
        showScreen(DLG_CONFIRM);
    }

    // ===== 单按钮通知弹窗 =====
    //
    // 复用确认弹窗的面板（同一套几何/字体/焦点纪律，**零新增布局**）：只把"取消"
    // 藏掉、把"确定"改文案，并记下要执行的动作。借用的代价是收尾必须把面板恢复成
    // 确认形态，否则下次真确认会少一个按钮 —— 恢复动作统一放在 runConfirmAction 里。

    /** 当前弹的是通知（不是确认）：左/右键不许再移到已隐藏的"取消"上。 */
    private boolean noticeMode;
    /** 通知是否警告性质（只有"配对码错 5 次"这类才配感叹号图标）。 */
    private boolean noticeWarn;
    private int noticeAction;
    private static final int NOTICE_NONE = 0;
    /** 去配对页（进页即开新窗，输错用满次数后靠它换新码） */
    private static final int NOTICE_GOTO_PAIRING = 2;

    private void showNotice(String msg, String okLabel, int action, boolean warn) {
        noticeMode = true;
        noticeWarn = warn;
        noticeAction = action;
        confirmMsg.setText(msg);
        btnConfirmOk.setText(okLabel);
        // ★ 必须用 GONE 而不是 INVISIBLE：INVISIBLE 只是不画，**仍然占着布局位置** ——
        //   按钮行是"确定 240px + 24px + 取消 240px"整体居中的，藏掉取消之后确定就
        //   落在左半边（用户反馈："连接方式和配对模式的按钮错位了，不在横轴中央"）。
        //   GONE 让它彻底不参与布局。
        btnConfirmCancel.setVisibility(View.GONE);
        // 换装后按钮改成绝对定位：原厂单按钮弹窗恒在 (200,340)，双按钮才把第一个上移到
        // (200,274)。藏掉取消不会让确定自动归位，得手动挪。
        moveButtonTop(btnConfirmOk, 336);
        showScreen(DLG_CONFIRM);   // 警告图标的显隐由 showScreen 统一决定
    }

    /** 改弹窗按钮的 top 边距（绝对定位，用于单/双按钮之间归位）。 */
    private void moveButtonTop(TextView b, int topMarginPx) {
        android.widget.RelativeLayout.LayoutParams lp =
                (android.widget.RelativeLayout.LayoutParams) b.getLayoutParams();
        lp.topMargin = topMarginPx;
        b.setLayoutParams(lp);
    }

    /**
     * 把共用面板恢复成"双按钮确认"形态。
     *
     * <p>通知态借了这个面板（藏掉取消、确定挪到单按钮位），用过之后**必须**归位。
     * 两条收尾路径都要调它：正常按确定/取消走 {@link #finishNotice()}；
     * 被 MENU 直接关掉（{@code onMenuKey} 的 DLG_CONFIRM 分支）走这里。
     * {@link #showConfirm} 里也调一次兜底 —— 面板形态绝不能靠"上一条路径记得恢复"。
     */
    private void resetNoticePanel() {
        noticeMode = false;
        noticeAction = NOTICE_NONE;
        btnConfirmOk.setText("确定");
        moveButtonTop(btnConfirmOk, 270);
        btnConfirmCancel.setVisibility(View.VISIBLE);
    }

    /** 通知弹窗收尾：先恢复面板形态，再执行它带来的跳转。 */
    private void finishNotice() {
        int act = noticeAction;
        resetNoticePanel();
        if (act == NOTICE_GOTO_PAIRING) {
            // "请先与一台手机配对"的确定 = 进配对页那一步。走 enterPairingHome()：
            // 配对页从此是**当前这一态的家**（层级历史清零 → MENU = 退出软件），
            // 且不再重弹这条提示（它就是这条提示的产物）。
            // ★ 这里原来有一条"无线电处于致命错误态就落主界面看错误"的例外 —— 已删：
            //   无已配对设备时主界面**根本不该出现**（用户定版铁律），而错误在那一边
            //   照样看得见（配对页的"服务状态"块与主界面同一份正文，且两键都当重试用）。
            enterPairingHome();
        } else {
            closeDialog();
        }
    }

    /**
     * 进配对页的**唯一一道门**（用户定版）：用户主动"去配对"一律走这里。
     *
     * <p>门有两副面孔，判据只有一个 —— 本机有没有已配对设备：
     * <ul>
     *   <li>**一台都没有** → 先弹单按钮通知"请先与一台手机配对 / 进入配对模式"
     *       （与首启第②步同一条，文案一字不改），按确定才进配对页；</li>
     *   <li>已有设备 → 弹原来的双按钮确认"进入配对模式？此操作会断开与手机端的连接"。</li>
     * </ul>
     * 两句话说的是两件事，不能互换：没有设备可断的时候谈"会断开与手机端的连接"是假话。
     *
     * <p>用户原话："无论何时只要本机中没有已配对设备，进入配对界面的前一步都要进行
     * 弹窗提示配对。" 所以进配对页的每条**用户来路**（选项菜单的"进入配对模式"、
     * 菜单 MENU 在没设备时的那一跳、启动时自动进）都收口到这里；唯一的例外是这条
     * 通知自己的"确定" —— 它就是门内那一跳，不能再回到门上（否则弹窗死循环）。
     */
    private void requestPairing() {
        if (hasPairedDevice()) {
            showConfirm(CONFIRM_PAIRING);
        } else {
            promptPairingRequired();
        }
    }

    // notePairingBack 已删（本轮 MENU 铁律）：它把"上一级"按三种来路写进 pairingBack，
    // 而现在"上一级"由 navHistory 弹栈得到 —— 同一个'进配对页'动作，来的路不同、
    // 栈里记的就不同，不需要屏自己再分类。

    private void runConfirmAction(boolean ok) {
        // 通知弹窗优先：它借的是同一个面板，但语义不是"确认/取消"
        if (noticeMode) {
            finishNotice();
            return;
        }
        int what = pendingConfirm;
        if (what == CONFIRM_UNPAIR) {
            String id = pendingUnpairId;
            pendingUnpairId = null;
            if (!ok || id == null) {
                showScreen(SCR_PAIRED);   // 取消：回设备列表接着挑
                return;
            }
            if (pairingStore != null) {
                AppLog.w("Pair", "解除配对 " + id);
                pairingStore.remove(id);
                notifyPeerUnpaired(id);
            }
            rebuildPairedItems();
            refreshMenuValues();
            ((BaseAdapter) pairedList.getAdapter()).notifyDataSetChanged();
            ((BaseAdapter) menuList.getAdapter()).notifyDataSetChanged();
            // ★ 用户定版：解除一台之后**留在"已配对设备"页**，不要直接跳进配对屏 ——
            //   一是解除之后还得能看清"还剩哪几台"（刚解错一台就能接着解下一台），
            //   二是"要不要再配一台"该由用户决定。要去配对室有路可走：
            //   设备页 MENU → 选项菜单 → （一台不剩时）MENU → 配对页（前一步必弹提示）。
            showScreen(SCR_PAIRED);
            return;
        }
        if (!ok) {
            closeDialog();            // 取消：回弹窗下面那一屏
            return;
        }
        if (what == CONFIRM_EXIT) {
            // ★ 不切屏：beginOrderlyExit() 自己会摆出"正在退出···"的浮层，
            //   背景必须保持弹窗底下那一屏（菜单/设备列表），切回主界面是错的 ——
            //   用户看到"点确定后背景跳到主界面"，就是这个 showScreen(SCR_MAIN) 干的。
            beginOrderlyExit();
        } else if (what == CONFIRM_PAIRING) {
            // 用户主动进配对模式（本机**已有**已配对设备才会走到这里 —— 一台都没有时
            // requestPairing() 走的是"请先与一台手机配对"那条通知，见那里的注释）。
            // ★ 进配对模式先**请走已连的手机**（用户要求，弹窗文案已写明）。
            //   配对模式的语义是"配一台新手机"，老会话握着一份马上要作废的码
            //   没有意义：它的界面还写着"已连接"，实际什么也做不了。
            //   只断会话不停服务 —— 监听端口与配对窗口保持存活，新手机照常连得进来。
            if (ptpServer != null) {
                try {
                    ptpServer.dropAllConnections();
                } catch (Throwable t) {
                }
            }
            showScreen(SCR_PAIRING);
        } else {
            showScreen(SCR_MAIN);
        }
    }

    private void onModeActivated(int pos) {
        AppLog.i("UI", "连接设置选中第 " + pos + " 项");
        // 连接设置里只有两项：连接到 Wi-Fi 网络 / 使用相机热点
        pendingMode = pos == 0 ? MODE_WIFI : MODE_HOTSPOT;
        showScreen(DLG_DEFAULT);
    }

    /**
     * 模式选择收尾（"设为默认/仅本次"回答之后）。
     *
     * <p>★ 用户定版的流程改动：**选完连接方式不等服务启动完成就去配对页**。
     * 服务（无线电 + PTP/IP）在后台照常起来，界面不等它 —— 相机热点/Wi-Fi 关联
     * 要好几秒，让用户盯着"正在启动…"干等没有任何意义，而配对页在服务起来之前
     * 就已经能把 6 位码显示出来（配对窗口与服务器是两件事，窗口先开、服务器后到，
     * 手机连上来时服务器再查窗口状态即可）。
     *
     * <p>落地由 {@link #landAfterModeChoice} 决定；本机没有已配对设备时**不直接进配对页**，
     * 而是先弹"请先与一台手机配对"（用户定版：提示是进配对流程的前一步）。
     */
    private void finishModeChoice(boolean asDefault) {
        // 选完去哪儿 —— 三种来路，**不能混为一谈**（用户明确指出之前把它们搞混了）：
        //   ① 从配对页菜单进来的（modeFromPairing）→ 回配对页，不弹提示；
        //   ② **首次启动**（不是从菜单进、本机又没有已配对设备）→ 配对流程，
        //      先弹"请先与一台手机配对"（引导第②步），按确定才进配对页；
        //   ③ 其余（主界面菜单进来改连接方式）→ 回主界面，不问也不提示。
        // ★ 判据必须是 hasPairedDevice()：老写法 `pairingStore != null && size() == 0`
        //   在首启那一刻恒为假（那时配对表还没建），界面于是停在主界面不动 —— 用户实测。
        final boolean firstLaunch = !modeFromMenu && !modeFromPairing && !hasPairedDevice();
        final boolean toPairing = modeFromPairing || firstLaunch;
        modeFromPairing = false;
        // 落地屏与"要不要弹配对提示"交给 beginModeSwitch 的完成回调（见那里的注释：
        // 原先回调写死 SCR_MAIN，把首启的配对流程整个跳过了）。
        modeSwitchTarget = toPairing ? SCR_PAIRING : SCR_MAIN;
        if (pendingMode != null) {
            final String newMode = pendingMode;
            // 首次启动没有"仅本次"可言 —— 不记住的话下次开机又得重选一遍连接方式。
            final boolean def = asDefault || savedMode == null;
            pendingMode = null;
            if (newMode.equals(savedMode)) {
                // 相同方式：只更新默认记忆，服务不动
                if (def) prefs.edit().putString(KEY_MODE, savedMode).commit();
                refreshMenuValues();
                landAfterModeChoice(toPairing);
                return;
            }
            beginModeSwitch(newMode, def);
            // beginModeSwitch 会先摆出"正在切换模式···"的浮层。从配对页菜单进来的话
            // 这里立刻垫上配对页（服务在后台起来，界面不等它）；其余情形不垫 ——
            // 垫了会把切换浮层一帧盖掉，看着像闪了一下，而"没配过设备"那一支本来就
            // 要在切换跑完后先弹提示（完成回调负责落地 + 弹提示）。
            if (toPairing && hasPairedDevice() && !firstLaunch) {
                showScreen(SCR_PAIRING);
            }
            return;
        }
        landAfterModeChoice(toPairing);
    }

    /**
     * 连接方式选定之后的落地：配对页 or 主界面。
     *
     * <p>★ 这里**只表达意愿**（"我想落到配对页/主界面"），合不合规由 {@link #showScreen}
     * 的守卫判：本机没有已配对设备时主界面立不起来（改走配对页 + 前一步的配对提示）、
     * 配对页也只能经由提示进。以前这些判断在三个调用点各写一遍（用户实测报的
     * "菜单里换连接方式 → 直接进主界面"就是漏了其中一处），现在只有一份。
     */
    private void landAfterModeChoice(boolean toPairing) {
        showScreen(toPairing ? SCR_PAIRING : SCR_MAIN);
    }

    /**
     * 首启引导第②步 / 所有"没配过设备就进配对页"的必经之弹：提示"请先与一台手机配对"，
     * 按确定才进配对流程（用户定版）。
     *
     * <p>复用单按钮通知弹窗（零新增布局）。文案是用户定死的，一字不改；确定键的动作是
     * {@code NOTICE_GOTO_PAIRING} —— 通知自己负责跳进配对页（那时 6 位码才开始走）。
     *
     * <p>★ "上一级"不用再挑（换装那版要在这里特判 DLG_EXITING/DLG_DEFAULT 两个浮层，
     * 把落点硬掰到连接设置面板）：现在浮层的上一级就是 baseScreen 记着的"最近一次显示的
     * 基础屏"—— 首启与"正在切换模式···"底下压着的**都是**连接设置面板，同一件事由同一份
     * 状态给出。用户按 MENU 看到的正是"回到我刚才选连接方式那一屏"。
     */
    private void promptPairingRequired() {
        // 按钮文案是"确定"（用户定版）：它是**告知 + 确认**，不是"点这里进入配对模式"
        // 那种动作按钮 —— 动作按钮的措辞会让人以为按了才开始配对，而提示的确定只是承认
        // 这件事（真正进配对页是它之后的落点）。
        showNotice("请先与一台手机配对", "确定", NOTICE_GOTO_PAIRING, false);
    }

    /**
     * 还没选连接方式时的提示（用户要求）：**先弹这一句，确定之后才是选择界面**。
     *
     * <p>为什么要有这一步：没有连接方式时这个 App 什么都做不了（没有服务、没有热点、
     * 配对页也没有意义），"请选择连接方式"是唯一该说的一句话。选择界面前面加一道确认，
     * 用户才会明白后面那个面板是"必须选一个"而不是"可看可不看"。
     *
     * <p>浮层底下就是那块连接设置面板（先立屏、再提示），所以按取消/MENU 退回来时
     * 看到的是"我刚才选连接方式那一屏"，不是黑屏。
     */
    private void promptModeChoice() {
        showNotice("请选择连接方式", "确定", NOTICE_NONE, false);
    }

    /**
     * 无已配对设备时"进配对页"的入口：**配对页就是这一态的家**。
     *
     * <p>它只做两件与普通切屏不同的事，其余交给 {@link #showScreen}：
     * <ol>
     *   <li>**层级历史清零**（由 showScreen 里"target == 配对页 && 无设备"那条做）——
     *       用户定版：首次启动这种"应用自动把你放进来的"配对页，MENU 就是退出软件
     *       （你来这一屏不是自己走的，上一屏没有意义）；</li>
     *   <li>**不弹"请先与一台手机配对"**：调用方自己安排 —— 要么刚问过（提示的确定
     *       就是这一步），要么马上就要把提示盖上去（启动时那条）。所以这里用
     *       {@code enteringPairingHome} 告诉 showScreen"这不是从别处进配对页"。</li>
     * </ol>
     *
     * <p>用户自己从菜单点"进入配对模式"进来的那一支**不走这里**（那一支本机有设备）：
     * 它走普通切屏 + 入栈，MENU 就是"返回刚才那一屏"（用户定版的两条，判据落在
     * "谁把用户带进来的"上，不是写死的界面规则）。
     */
    private void enterPairingHome() {
        enteringPairingHome = true;
        showScreen(SCR_PAIRING);
        enteringPairingHome = false;
    }

    // ===== 连接方式应用与切换 =====

    /**
     * 刷两份菜单里那两行的"当前值"（连接方式 / 已配对台数）。
     *
     * <p>按**动作码**找到该刷哪一行，不按下标：两份模型项数不同（配对模式下"进入配对
     * 模式"隐藏），写死下标迟早串行。以后再加一份菜单模型也不用改这里。
     */
    private void refreshMenuValues() {
        String v = MODE_WIFI.equals(savedMode) ? "Wi-Fi"
                : MODE_HOTSPOT.equals(savedMode) ? "热点" : "未设置";
        int count = pairingStore != null ? pairingStore.size() : 0;
        String p = count > 0 ? count + " 台" : "无";
        fillMenuValues(menuItems, v, p);
        fillMenuValues(menuItemsPairingMode, v, p);
    }

    private static void fillMenuValues(String[][] model, String modeValue, String pairedValue) {
        for (int i = 0; i < model.length; i++) {
            if (ACT_MODE.equals(model[i][2])) {
                model[i][1] = modeValue;
            } else if (ACT_PAIRED.equals(model[i][2])) {
                model[i][1] = pairedValue;
            }
        }
    }

    // ===== 配对页（与"已配对设备"是两个界面，见 STATUS）=====
    //
    // 配对与"已配对设备"是**两个界面**（用户要求）：
    //   · 本页（SCR_PAIRING）正文是**三块互斥内容 + 一排常驻按钮**（与主界面同一套结构，
    //     见字段区那段 ★）：0=6 位码 + 倒计时 + 提示，1=热点信息，2=服务状态。
    //     其余与主界面一致（上下两条 #333333 细线、应用图标+标题、右上电量、按键引导）。
    //   · 设备清单在 SCR_PAIRED，样式与选项菜单相同，可滚动，中键（ENTER）解除配对。

    /**
     * 进入配对页：开窗（6 位码 + 180s），启动 1s 倒计时心跳。
     *
     * <p>配对窗口**不依赖服务**：服务还没起来也能先显示码，服务器随后才到 ——
     * 手机连上来时服务器才查"窗口开着没、码对不对"，所以顺序无所谓。
     * 这正是"选完连接方式不等服务启动完成就进配对页"能成立的原因。
     */
    private void enterPairingMode() {
        AppLog.i("Pair", "进入配对模式（等待手机连接，手机来连才亮码）");
        if (pairingStore == null) {
            return;
        }
        // ★ 用户要求：进配对页**先不亮码**，给出"等待手机连接"的环节 ——
        //   手机端选定这台相机、发起配对（PAIR_BEGIN 来取码）的那一刻才开窗亮码
        //   （懒开窗在 PtpCameraHandler.pairingCode()）。这里只把"配对模式激活"
        //   的标记交给协议层（探测应答 pairingMode 报真，手机扫得到这台相机）。
        if (ptpHandler != null) {
            ptpHandler.setPairingUiActive(true);
        }
        updatePairingScreen();
        handler.removeCallbacks(pairingTicker);
        handler.postDelayed(pairingTicker, 1000);
    }

    /**
     * 设备码的**显示形式**：只取前 8 位（用户定版）。
     *
     * <p>完整设备码是 16 位 hex（8 字节）。前 8 位 = 4 字节，区分"同一台相机配过的几台手机"
     * 完全够用，字数和行宽也舒服得多。**只在显示这一层截断** —— 协议比较、配对表主键、
     * 落盘文件里仍然一律是完整 16 位，别把截断带进数据层。
     */
    private static String shortCode(String hex) {
        if (hex == null || hex.length() == 0) {
            return "—";
        }
        return hex.length() <= 8 ? hex : hex.substring(0, 8);
    }

    /**
     * 关于页正文：固定信息 + **本机设备码**（用户要求加在"开发者"下方）。
     *
     * <p>设备码取的就是配对表落盘的那 16 位小写 hex（`device_id.hex`）—— 那是**本机
     * （相机）自己的**码，随机生成一次后永不变，与协议里 `handler.guid()` 返回的是同一个值。
     * 原样取、不改大小写，截到前 8 位显示（见 {@link #shortCode}）。
     *
     * <p>★ 第二行"配套手机端"是用户要求加的，与手机端关于页那句"配套相机端…"**对仗**：
     * 两头各自说清"这个版本配哪一端的哪个版本"，排障时一眼能对上。
     */
    private void updateAboutText() {
        String id = pairingStore != null ? pairingStore.deviceIdHex() : null;
        aboutTitle.setText("SonyConnect 2.0");
        aboutMeta.setText("配套手机端 SonyConnect 2.0 使用\n开发者：BI2QFA");
        aboutCode.setText("本机设备码：" + shortCode(id));
    }

    /**
     * 日志屏刷新：把内存里的调试日志铺进 TextView，并滚到最底（最新一条）。
     *
     * <p>屏幕上一屏最多铺 {@link #LOG_SHOW_LINES} 行 —— 相机上 TextView 的排版开销与
     * 行数成正比，几百行够看清一个回合；内存里 AppLog 存得更多（800 行）。
     * 滚到底用 post 延迟一次：进屏这一刻 TextView 还没量完高度，直接 fullScroll 会无效。
     */
    private void updateLogScreen() {
        if (logText == null) {
            return;
        }
        logText.setText(AppLog.dump(LOG_SHOW_LINES));
        if (logScroll != null) {
            logScroll.post(new Runnable() {
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    /** 离开配对页：关窗（作废当前码），停倒计时心跳。 */
    private void leavePairingMode() {
        AppLog.i("Pair", "离开配对页（关窗作废当前码）");
        handler.removeCallbacks(pairingTicker);
        if (ptpHandler != null) {
            ptpHandler.setPairingUiActive(false);
        }
        if (pairingStore != null) {
            pairingStore.closeWindow();
        }
        refreshMenuValues();
        ((BaseAdapter) menuList.getAdapter()).notifyDataSetChanged();
    }

    /** 重建"已配对设备"行模型：每台已配对手机一行，**值列留空**（解除的提示在底栏）。 */
    private void rebuildPairedItems() {
        if (pairingStore == null || pairingStore.size() == 0) {
            pairedItems = new String[][]{{"无已配对设备", ""}};
        } else {
            java.util.List<PairingStore.Paired> all = pairingStore.all();
            pairedItems = new String[all.size()][];
            for (int i = 0; i < all.size(); i++) {
                PairingStore.Paired pd = all.get(i);
                String name = pd.peerName != null && pd.peerName.length() > 0
                        ? pd.peerName : pd.peerDeviceId;
                // 值列 = **手机的设备码前 8 位**（用户要求：就写在原来"ENTER 解除"那个位置，
                // 只写码、不加任何字，且只显示前 8 位 —— 区分设备够了）。
                // 它是配对表主键（完整 16 位）的显示形式，同型号两台手机靠它区分。
                pairedItems[i] = new String[]{name, shortCode(pd.peerDeviceId)};
            }
        }
        ((RowAdapter) pairedList.getAdapter()).setItems(pairedItems);
        pairedShownCount = pairingStore == null ? 0 : pairingStore.size();
        if (pairedFocus >= pairedItems.length) {
            pairedFocus = 0;
        }
    }

    /**
     * 刷新码与倒计时；窗口过期则显示"配对已结束"。
     *
     * <p>三行分开：配对码（大号橙）／倒计时／提示。用户要求"配对码和倒计时等信息显示
     * 应居中"，所以这里是三行各自居中、整体由布局 layout_gravity=center 居中。
     */
    private void updatePairingScreen() {
        if (pairingStore == null) {
            pairingCodeText.setText("- - - - - -");
            pairingCountdownText.setText("");
            pairingHintText.setText("");
            return;
        }
        long now = System.currentTimeMillis();
        String code = pairingStore.code(now);
        if (code == null) {
            // ★ 等待手机连接（用户要求）：进配对页先不亮码，手机端选定这台相机、
            //   发起配对的那一刻 PtpCameraHandler 才开窗（懒开窗）。开窗前配对页
            //   只显示占位与"等待手机连接"。窗口 180s 到期回到这里也一样 ——
            //   手机再发起一轮配对就会换一张新码（懒开窗兜底）。
            pairingCodeText.setText("- - - - - -");
            pairingCountdownText.setText("");
            pairingHintText.setText("等待手机连接…");
            return;
        }
        StringBuilder pretty = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0) pretty.append(' ');
            pretty.append(code.charAt(i));
        }
        pairingCodeText.setText(pretty.toString());
        long left = (pairingStore.remainingMs(now) + 999) / 1000;
        pairingCountdownText.setText("此配对码有效期剩余 " + left + " 秒");
        // 提示只剩一句话；"已配对 N 台"的指示删掉（用户要求），台数去"已配对设备"页看
        pairingHintText.setText("确保相机端软件正常运行");
    }

    /**
     * 已配对设备页 ENTER：弹确认框问是否解除（相机端删记录，手机端下次需重新配对）。
     *
     * <p>确认框用的是**同一个**通用弹窗（原厂带感叹号图标那款），与"退出软件"同款。
     */
    private void onPairedActivated(int pos) {
        AppLog.i("UI", "已配对设备选中第 " + pos + " 项");
        if (pairingStore == null || pairingStore.size() == 0) {
            return;
        }
        java.util.List<PairingStore.Paired> all = pairingStore.all();
        if (pos < 0 || pos >= all.size()) {
            return;
        }
        final PairingStore.Paired pd = all.get(pos);
        String name = pd.peerName != null && pd.peerName.length() > 0 ? pd.peerName : pd.peerDeviceId;
        pendingUnpairId = pd.peerDeviceId;
        confirmMsg.setText("解除与 " + name + " 的配对？");   // showConfirm 的 UNPAIR 分支不动文案
        showConfirm(CONFIRM_UNPAIR);
    }

    private void applyMode() {
        AppLog.i("Mode", "applyMode：" + savedMode);
        refreshMenuValues();
        updateMainStatus();
        if (MODE_HOTSPOT.equals(savedMode)) {
            initAndStartWifiCycle(true);
        } else {
            startStationFlow();
        }
    }

    /**
     * 切换连接方式（用户要求）：完整走一遍退出流程——按旧模式有序停服务、
     * 无线电归位、恢复 APO——完成后再按新模式启动。
     */
    private void beginModeSwitch(final String newMode, final boolean asDefault) {
        final String oldMode = savedMode;
        AppLog.w("Mode", "切换连接方式 " + oldMode + " → " + newMode + "（设为默认=" + asDefault + "）");
        shuttingDown = true;      // 切换期间吞按键
        stationWaiting = false;
        modeSwitching = true;     // 文案显示"正在切换"
        // 关键：撤掉旧模式遗留的延时回调（5s 延时使能、30s 启动看门狗）。
        // 否则看门狗会在新模式的 Wi-Fi 轮询期间误触发 goFatal 强关无线电，
        // 表现为"已切到 Wi-Fi 模式却连不上网"。
        removeRadioCallbacks();
        // ★ 先告诉已连的手机"我在换连接方式"（用户要求：切换方式也要通知手机）。
        //   顺序是硬的：必须在下面那个线程跑 shutdownServicesAndRadio → ptpServer.stop()
        //   之前发出 —— 那条路一拆连接就没通道可送了，而 ptpServer.stop() 只会发
        //   "相机端已退出"，手机端于是显示一句假话（相机明明开着、界面还写着
        //   "正在切换模式···"，用户却看到"相机端已退出"）。发过这条之后，本次会话的
        //   退出通知会被抑制（见 PtpIpServer#pushAppExiting）。
        //   ★ 只写进 socket **不等于**送到：热点模式下手机连的是相机自己那个 AP，
        //   而切换的第一步就是把 AP 拆掉 —— 包还在发送缓冲里、链路已经没了。
        //   所以下面那个线程要先等一下再拆（见 noticeSent）。
        boolean noticeSent = false;
        if (ptpServer != null) {
            try {
                ptpServer.pushModeSwitching(MODE_HOTSPOT.equals(newMode)
                        ? PtpCodec.MODE_CODE_HOTSPOT
                        : PtpCodec.MODE_CODE_WIFI);
                noticeSent = true;
            } catch (Throwable ignored) {
                // 通知是锦上添花：没有事件通道也不该挡住切换本身
            }
        }
        final boolean noticePushed = noticeSent;
        phase = PH_CLOSING;
        // ★ 切换期间给一个明确的全屏提示（原先只在主界面的状态文本里写"正在切换…"，
        //   而那时界面可能停在连接设置/二维码上，用户看不到任何反馈）。
        exitMsgView.setText("正在切换模式···");
        showScreen(DLG_EXITING);
        new Thread("ModeSwitch") {
            public void run() {
                if (noticePushed) {
                    // ★ 用户报"热点切成 Wi-Fi 时手机端没提示"，原因就在这里：
                    //   那条通知只是**写进了 socket 缓冲**，而热点模式下手机连着的是
                    //   相机自己这个 AP —— 紧接着的 shutdownServicesAndRadio 会把 AP 拆掉，
                    //   包还没发出去链路就没了（TCP 的"写成功"从来不保证"送到"）。
                    //   留 600ms 让这张几十字节的小包真的穿过 AP、被手机读到，再开始拆。
                    //   相机界面此刻正显示"正在切换模式···"，这 0.6 秒用户看不出来；
                    //   另一头（Wi-Fi → 热点）手机的链路是接到路由器上的，本不需要等，
                    //   但两个方向都走同一条代码路径更不容易出错。
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException ignored) {
                        // 被打断也照样往下走：切换不能因为一次通知没发全就停下
                    }
                }
                shutdownServicesAndRadio(oldMode);
                handler.post(new Runnable() {
                    public void run() {
                        shuttingDown = false;
                        modeSwitching = false;
                        savedMode = newMode;
                        if (asDefault) {
                            prefs.edit().putString(KEY_MODE, savedMode).commit();
                        }
                        // ★ 落地屏由发起方给（modeSwitchTarget），不再写死主界面 ——
                        //   首启/配对页进来的要回配对页，见 finishModeChoice。
                        //   两屏的正文块都归位到第 0 块（主界面=相机+手机示意，配对页=配对码）
                        //   —— 用户要求：不能停在切换前点开的服务状态/热点信息/二维码。
                        mainBlock = 0;
                        pairingBlock = 0;
                        // ★ 落地屏由发起方给（modeSwitchTarget），但**合不合规不由这里判** ——
                        //   本机没有任何已配对设备时主界面立不起来、配对页也只能经由配对
                        //   提示进，两条都在 showScreen 的守卫里（用户实测报过"菜单里换
                        //   连接方式 → 一台设备都没有却直接进了主界面"：那时这个判断在
                        //   三个调用点各写了一遍，漏了一处。现在只有一份）。
                        showScreen(modeSwitchTarget);
                        applyMode();
                    }
                });
            }
        }.start();
    }

    // ===== 热点模式：官方无线电状态机（SonyFTP v2.0 原样移植） =====

    /** 官方 onResume 的无线初始化段：首次、系统回到前台与 [确定键] 重试共用 */
    private void initAndStartWifiCycle(boolean manual) {
        if (shuttingDown) return;
        if (manual) watchdogRecoveries = 0;
        initFlags();
        phase = PH_STARTING;
        errorMsg = null;
        ssid = null;
        password = null;
        currentIp = HOTSPOT_IP;
        try {
            registerReceiver(receiver, iFilter);
        } catch (Throwable t) {
        }
        if (wifiManager != null && wifiManager.getWifiState() != WifiManager.WIFI_STATE_DISABLED) {
            isDisableActionFiltered = false;
            isInitialDisabling = true;
            try {
                wifiManager.setWifiEnabled(false);
            } catch (Throwable t) {
            }
        } else {
            isEnableActionFiltered = false;
            try {
                wifiManager.setWifiEnabled(true);
            } catch (Throwable t) {
            }
        }
        // 整链看门狗：正常路径最迟应在组创建成功时进入 PH_RUNNING 而解除
        handler.removeCallbacks(stalledStartupCheck);
        handler.postDelayed(stalledStartupCheck, 30000);
        updateMainStatus();
    }

    private void initFlags() {
        isInitialDisabling = false;
        isDisableActionFiltered = true;
        isEnableActionFiltered = true;
        isGroupCreateActionFiltered = true;
        isDisablingForFinish = false;
        isRetrying = false;
        isDirectEnableRetrying = false;
    }

    private boolean isAirplaneModeOn() {
        try {
            return Settings.System.getInt(getContentResolver(), "airplane_mode_on", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 无线电广播的关键 extra 短描述（日志用）。 */
    private String shortExtras(Intent it) {
        try {
            StringBuilder sb = new StringBuilder();
            if (it.hasExtra(WifiManager.EXTRA_WIFI_STATE)) {
                sb.append(" wifiState=").append(it.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE, -1));
            }
            if (it.hasExtra(RadioWrapper.EXTRA_STATE)) {
                sb.append(" direct ").append(it.getIntExtra(RadioWrapper.EXTRA_PREV_STATE, -1))
                        .append("→").append(it.getIntExtra(RadioWrapper.EXTRA_STATE, -1));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void handleEvent(Intent intent) {
        // 热点启动状态机守卫：只允许"热点模式 + 非关闭/切换期"驱动本状态机。
        // 此前切到 Wi-Fi 模式后接收器未注销，startStationFlow 一开 Wi-Fi 总开关，
        // WIFI_STATE_ENABLED 广播就把这套状态机唤醒——它继续去使能 Direct、
        // 重建 GO 组，失败即报"无法启用Direct模式"并回滚关掉 Wi-Fi，
        // 把刚拆干净的热点又复活。
        if (shuttingDown || !MODE_HOTSPOT.equals(savedMode)) return;
        String action = intent.getAction();
        String act = action == null ? "null" : action.substring(action.lastIndexOf('.') + 1);
        AppLog.i("Radio", "事件 " + act + shortExtras(intent));
        if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
            handleWifiStateChanged(intent.getIntExtra(
                    WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN));
        } else if (RadioWrapper.ACTION_DIRECT_STATE_CHANGED.equals(action)) {
            int prev = intent.getIntExtra(RadioWrapper.EXTRA_PREV_STATE, RadioWrapper.DIRECT_STATE_UNKNOWN);
            int cur = intent.getIntExtra(RadioWrapper.EXTRA_STATE, RadioWrapper.DIRECT_STATE_UNKNOWN);
            handleDirectStateChanged(prev, cur);
        } else if (RadioWrapper.ACTION_GROUP_CREATE_SUCCESS.equals(action)) {
            handleGroupCreateSuccess((RadioWrapper.GroupConfig)
                    intent.getParcelableExtra(RadioWrapper.EXTRA_CONFIG));
        } else if (RadioWrapper.ACTION_STA_DISCONNECTED.equals(action)) {
            // FTP/协议会话随客户端断开自行收尾，无需动作
        } else if (RadioWrapper.ACTION_GROUP_CREATE_FAILURE.equals(action)) {
            handleGroupCreateFailure(intent.getIntExtra(RadioWrapper.EXTRA_STATE, -1));
        } else if (RadioWrapper.ACTION_STA_CONNECTED.equals(action)) {
        }
    }

    private void handleWifiStateChanged(int state) {
        curWifiMgrState = state;
        switch (state) {
            case WifiManager.WIFI_STATE_DISABLED:
                if (isDisableActionFiltered) {
                } else if (isInitialDisabling) {
                    isInitialDisabling = false;
                    isEnableActionFiltered = false;
                    handler.postDelayed(delayedWifiEnabler, DELAY_WIFI_ENABLE_MS);
                } else if (isDisablingForFinish) {
                } else if (isRetrying) {
                    delayFatalCheck();
                } else {
                    isRetrying = true;
                    handler.postDelayed(delayedWifiEnabler, DELAY_WIFI_ENABLE_MS);
                }
                return;
            case WifiManager.WIFI_STATE_ENABLING:
                isDisableActionFiltered = false;
                return;
            case WifiManager.WIFI_STATE_ENABLED:
                if (isEnableActionFiltered) {
                    return;
                }
                isRetrying = false;
                issueDirectEnable();
                return;
            case WifiManager.WIFI_STATE_UNKNOWN:
                if (isDisableActionFiltered) {
                    return;
                }
                if (isRetrying) {
                    delayFatalCheck();
                    return;
                }
                isRetrying = true;
                handler.postDelayed(delayedWifiEnabler, DELAY_WIFI_ENABLE_MS);
                return;
            default:
                return;
        }
    }

    private void handleDirectStateChanged(int previousState, int currentState) {
        AppLog.i("Radio", "Direct 状态 " + previousState + " → " + currentState);
        curWifiDirectMgrState = currentState;
        switch (currentState) {
            case RadioWrapper.DIRECT_STATE_DISABLED:
                if (previousState == RadioWrapper.DIRECT_STATE_ENABLED) return;
                if (isDisableActionFiltered) {
                } else if (isDisablingForFinish) {
                } else if (isInitialDisabling) {
                    // 初次复位阶段的 Direct 关闭属正常
                } else {
                    if (isRetrying) {
                    } else {
                        delayFatalCheck();
                    }
                }
                return;
            case RadioWrapper.DIRECT_STATE_ENABLED:
                isRetrying = false;
                isDirectEnableRetrying = false;   // 应答已到，撤销重试计划
                handler.removeCallbacks(delayedDirectEnableRetry);
                if (isEnableActionFiltered) {
                    return;
                }
                startGroupOwner();
                return;
            case RadioWrapper.DIRECT_STATE_UNKNOWN:
                if (isDisableActionFiltered) {
                    return;
                }
                delayFatalCheck();
                return;
            default:
                return;
        }
    }

    private void handleGroupCreateSuccess(RadioWrapper.GroupConfig cfg) {
        isRetrying = false;
        if (isGroupCreateActionFiltered) {
            return;
        }
        handler.removeCallbacks(stalledStartupCheck);
        handler.removeCallbacks(delayedDirectEnableRetry);
        if (cfg != null) {
            ssid = cfg.ssid;
            password = cfg.preSharedKey;
        }
        currentIp = HOTSPOT_IP;
        if (!startServices()) {
            goFatal("FTP 启动失败");
            return;
        }
        disableAutoPowerOff();
        phase = PH_RUNNING;
        AppLog.i("Radio", "热点就绪：SSID=" + ssid + " IP=" + currentIp);
        updateMainStatus();
    }

    private void handleGroupCreateFailure(int err) {
        if (isGroupCreateActionFiltered) {
        } else if (isRetrying) {
            goFatal("热点创建失败(" + err + ")");
        } else {
            isRetrying = true;
            startGroupOwner();
        }
    }

    private void startGroupOwner() {
        isGroupCreateActionFiltered = false;
        String deviceName = Build.MODEL != null ? Build.MODEL : "SonyConnect";
        radio.configureIdentity(deviceName, deviceName, deviceName);
        RadioWrapper.GroupConfig live = radio.getLiveGroup();
        int netId = live != null ? live.networkId : RadioWrapper.NET_ID_PERSISTENT_GO;
        if (!radio.startGo(netId)) {
            handleGroupCreateFailure(-3);
        }
    }

    private void delayFatalCheck() {
        handler.postDelayed(delayedFatalCheck, DELAY_FATAL_CHECK_MS);
    }

    /**
     * 发出 Direct 使能命令。命令级失败（超时/拒绝）不再误入组创建路径：
     * 第一次走 5 秒后重发，仍失败才报致命；期间若 DIRECT_STATE_ENABLED
     * 广播到达（慢应答），重试会被自动撤销。
     */
    private void issueDirectEnable() {
        boolean acked = false;
        try {
            acked = radio.setDirectEnabled(true);
        } catch (Throwable t) {
        }
        if (acked) return;
        if (!isDirectEnableRetrying) {
            isDirectEnableRetrying = true;
            handler.postDelayed(delayedDirectEnableRetry, DELAY_WIFI_ENABLE_MS);
        } else {
            String detail = radio.getLastError();
            goFatal("无法启用Direct模式" + (detail != null && detail.length() > 0 ? "\n" + detail : ""));
        }
    }

    private void goFatal(String reason) {
        AppLog.e("Radio", "启动失败：" + reason.replace((char) 10, ' '));
        errorMsg = reason;
        phase = PH_FAILED;
        handler.removeCallbacks(stalledStartupCheck);
        handler.removeCallbacks(delayedDirectEnableRetry);
        // ★ 启动失败必须让用户**看得见原因**：失败文案与"[确定键] 重试"都在"服务状态"
        //   那一块正文里（主界面与配对页各一块，同一份文本）。
        //   老写法是"把界面拉回主界面"—— 现在两处都不成立：① 本机没有已配对设备时
        //   主界面根本不该出现（用户定版铁律，showScreen 也会拦）；② 拉过去也未必停在
        //   "服务状态"块上，照样看不到原因。
        //   改成**不换屏**：把当前屏的正文块切到"服务状态"。用户在配对页（无设备的那一态）
        //   或主界面上，一抬眼就是失败原因；两屏的按钮都当重试用（见 onBlockButton）。
        if (screen == SCR_PAIRING) {
            pairingBlock = 2;
            refreshScreens();
        } else if (screen == SCR_MAIN) {
            mainBlock = 2;
            refreshScreens();
        }
        // 官方遇致命错误仅切界面；我们按既定纪律补一次安静回滚，避免污染系统
        if (radio != null) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        radio.setDirectEnabled(false);
                    } catch (Throwable t) {
                    }
                    try {
                        wifiManager.setWifiEnabled(false);
                    } catch (Throwable t) {
                    }
                }
            }, "RadioRollback").start();
        }
        updateMainStatus();
    }

    private void removeRadioCallbacks() {
        handler.removeCallbacks(delayedWifiEnabler);
        handler.removeCallbacks(delayedFatalCheck);
        handler.removeCallbacks(delayedDirectEnableRetry);
        handler.removeCallbacks(stalledStartupCheck);
        handler.removeCallbacks(stationPoll);
    }

    // ===== Wi-Fi 客户端模式（用户要求：启动即自动打开 Wi-Fi，不跳设置） =====

    private void startStationFlow() {
        if (shuttingDown) return;
        phase = PH_STARTING;
        errorMsg = null;
        currentIp = null;
        updateMainStatus();
        // 双保险①：热点遗留的延时回调（延时使能/看门狗）一并撤掉
        removeRadioCallbacks();
        // 双保险②：若组壳仍存活（切换时关闭命令丢失或沉降慢），removeGroup
        // 拆掉并确认消失，否则无线电被 GO 占用，Wi-Fi 关联不上——
        // "显示 Wi-Fi 实为热点"
        try {
            if (radio.getLiveGroup() != null) {
                long deadline = SystemClock.elapsedRealtime() + 8000;
                while (SystemClock.elapsedRealtime() < deadline) {
                    try {
                        radio.removeGroup();
                    } catch (Throwable t) {
                    }
                    try {
                        radio.issueDirectOff();
                    } catch (Throwable t) {
                    }
                    if (radio.getLiveGroup() == null) break;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
        }
        // Tweak EnableWifi 同款：总开关关着就打开。系统 supplicant 会自动连接
        // 系统里已绑定（保存过密码）的网络——不碰配置、不 reconnect、不跳设置。
        // 记录启动前的 Wi-Fi 状态：若是我们打开的，退出时按铁律关回（零残留）。
        try {
            if (wifiEnabledByUs == null) {
                wifiEnabledByUs = Boolean.valueOf(
                        wifiManager.getWifiState() != WifiManager.WIFI_STATE_ENABLED);
            }
        } catch (Throwable t) {
        }
        try {
            wifiManager.setWifiEnabled(true);
        } catch (Throwable t) {
        }
        stationPolls = 0;
        ssidRetries = 0;
        stationNudged = false;
        stationWaiting = true;
        handler.removeCallbacks(stationPoll);
        handler.postDelayed(stationPoll, 500);
    }

    private final Runnable stationPoll = new Runnable() {
        public void run() {
            if (shuttingDown || !stationWaiting || phase == PH_RUNNING) return;
            if (!MODE_WIFI.equals(savedMode)) return;
            int state;
            try {
                state = wifiManager.getWifiState();
            } catch (Throwable t) {
                state = WifiManager.WIFI_STATE_DISABLED;
            }
            // 使能竞态兜底：8 秒后总开关仍未打开，幂等补发一次（Tweak 同款调用）
            if (state != WifiManager.WIFI_STATE_ENABLED && stationPolls >= 16 && !stationNudged) {
                stationNudged = true;
                try {
                    wifiManager.setWifiEnabled(true);
                } catch (Throwable t) {
                }
            }
            // 服务就绪条件 = 总开关已开 且 DHCP 已拿到 IP。
            // 不接受"已关联但 IP 未下发"（此前 DHCP 晚一拍即误报"FTP 启动失败"）
            String ip = state == WifiManager.WIFI_STATE_ENABLED ? stationIpAddress() : null;
            if (ip != null) {
                // IP 已到手但 SSID 偶发滞后：最多再等 3 秒取名称再切运行态
                if (stationSsid() == null && ssidRetries < 6) {
                    ssidRetries++;
                    handler.postDelayed(this, 500);
                    return;
                }
                stationWaiting = false;
                currentIp = ip;
                ssid = stationSsid();
                if (!startServices()) {
                    goFatal("FTP 启动失败");
                    return;
                }
                disableAutoPowerOff();
                phase = PH_RUNNING;
                AppLog.i("Radio", "接入点就绪：SSID=" + ssid + " IP=" + currentIp);
                updateMainStatus();
                return;
            }
            if (stationPolls++ >= STATION_POLLS_MAX) {
                stationWaiting = false;
                phase = PH_FAILED;
                errorMsg = "未检测到 Wi-Fi 连接\n请检查网络后按确定键重试";
                updateMainStatus();
                return;
            }
            handler.postDelayed(stationPoll, 500);
        }
    };

    private String stationIpAddress() {
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) return null;
            int ip = info.getIpAddress();
            if (ip == 0) {
                try {
                    android.net.DhcpInfo dhcp = wifiManager.getDhcpInfo();
                    if (dhcp != null) ip = dhcp.ipAddress;
                } catch (Throwable t) {
                }
            }
            if (ip == 0) return null;
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                    + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
        } catch (Throwable t) {
            return null;
        }
    }

    private String stationSsid() {
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null || info.getSSID() == null) return null;
            String s = info.getSSID();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                s = s.substring(1, s.length() - 1);
            }
            // 关联刚完成时固件可能回占位串，视为未取到
            if (s.length() == 0 || "<unknown ssid>".equals(s)) return null;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 跳系统 Wi-Fi 设置：先开 Wi-Fi（Tweak 同款），再用相机自带设置页（真机验证
     *  标准 Intent 不存在；action 名取自 Tweak DeveloperActivity） */
    private void jumpToWifiSettings() {
        jumpingToSystem = true;
        boolean wifiOn = false;
        try {
            wifiOn = wifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED;
        } catch (Throwable t) {
        }
        try {
            wifiManager.setWifiEnabled(true);
        } catch (Throwable t) {
        }
        // ① Tweak 同款 action
        try {
            startActivity(new Intent("com.sony.scalar.app.wifisettings.WifiSettings"));
            return;
        } catch (Throwable t) {
        }
        // ② 显式组件（固件 WifiSettings.apk manifest）
        try {
            Intent i = new Intent();
            i.setClassName("com.sony.scalar.app.wifisettings",
                    "com.sony.scalar.app.wifisettings.WifiSettingsActivity");
            startActivity(i);
            return;
        } catch (Throwable t) {
        }
        // ③ 标准 Intent 兜底链
        boolean launched = false;
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            launched = true;
        } catch (Throwable t) {
        }
        if (!launched) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                launched = true;
            } catch (Throwable t) {
            }
        }
        if (!launched) {
            try {
                startActivity(new Intent("android.net.wifi.PICK_WIFI_NETWORK"));
                launched = true;
            } catch (Throwable t) {
            }
        }
        if (!launched) {
            jumpingToSystem = false;
        }
    }

    // ===== 服务链 =====

    private synchronized boolean startServices() {
        if (thumbPrefetcher == null) {
            thumbPrefetcher = new ThumbPrefetcher(getRootDir());
        }
        // pairingStore 在 onCreate 就已建好（见那里的注释）：它不依赖无线电与服务，
        // 而"有没有已配对设备"这件事必须**在服务起来之前**就能回答 —— 首启选完连接方式
        // 要立刻判断该不该进配对页，那时服务还在后台起。
        if (pairingStore == null) {
            pairingStore = new PairingStore(getFilesDir());
        }
        if (ptpServer == null) {
            try {
                PtpCameraHandler h = new PtpCameraHandler(getRootDir(), thumbPrefetcher,
                        pairingStore, new CameraPlatform());
                PtpIpServer s = new PtpIpServer(h);
                s.setPairingHandler(h);
                h.setPairingEvents(new PairingUiEvents());
                s.start();
                ptpServer = s;
                ptpHandler = h;
            } catch (IOException e) {
                ptpServer = null;
                ptpHandler = null;
                return false;
            }
        }
        // 静态分析定版：单进程铁律，设备信息全部纯 Java 进程内读取，无预热探针
        // 主屏状态轮询随服务起停（服务在跑才可能有手机连上来）
        AppLog.i("Ptp", "服务已就绪：proto端口=" + ptpServer.getProtoPort()
                + " file端口=" + ptpServer.getFilePort());
        handler.removeCallbacks(mainTicker);
        handler.postDelayed(mainTicker, 1000);
        return true;
    }

    /**
     * 停服务（退出链用）：先停缩略图预取线程，再停 PTP/IP。
     * 与旧的「先停私有协议里的预取器、再停服务器」保持同一相对顺序 ——
     * 预取线程必须先收，否则它可能正持有 SD 句柄。
     */
    private void stopPtpServer() {
        if (thumbPrefetcher != null) {
            try {
                thumbPrefetcher.stop();
            } catch (Throwable t) {
            }
            thumbPrefetcher = null;
        }
        stopPtpServerOnly();
    }

    /** 只停 PTP/IP 本身（不动预取器）；供需要自己掌握预取器生命周期的路径使用。 */
    private void stopPtpServerOnly() {
        AppLog.i("Ptp", "停服务");
        handler.removeCallbacks(mainTicker);
        if (ptpServer != null) {
            try {
                ptpServer.stop();
            } catch (Throwable t) {
            }
            ptpServer = null;
        }
        ptpHandler = null;
    }

    /**
     * 平台能力实现：**这是全工程唯一能合法引用 {@code Context} 去取设备信息的地方**。
     * {@link PtpCameraHandler} 保持零 Android 依赖（否则进不了桌面回归的空 classpath 编译），
     * 所以电量/型号/序列号/固件/镜头/模式/SSID 都从这里注入进去。
     */
    private class CameraPlatform implements PtpCameraHandler.Platform {
        public int batteryPct() {
            return DeviceInfo.getBatteryPct(MainActivity.this);
        }

        public String model() {
            return DeviceInfo.getModel(MainActivity.this, getRootDir());
        }

        public String serial() {
            return DeviceInfo.getSerial(MainActivity.this, getRootDir());
        }

        public String firmware() {
            return DeviceInfo.getFirmwareVersion();
        }

        /** 镜头反射带 2.5s TTL 缓存：3s 一次的 PING 节奏下每次都是新值，换镜头几秒内生效 */
        public String lens() {
            return DeviceInfo.getLens(MainActivity.this, getRootDir());
        }

        public String mode() {
            return MODE_HOTSPOT.equals(savedMode) ? "hotspot" : "wifi";
        }

        public String ssid() {
            if (MODE_HOTSPOT.equals(savedMode)) {
                return orEmpty(ssid);
            }
            String live = stationSsid();
            return orEmpty(live != null ? live : ssid);
        }

        // ===== 固定信息（随 DEVICE_INFO 一次性下发，不进心跳） =====

        public String region() {
            return DeviceInfo.getRegion();
        }

        public String apiVersion() {
            return DeviceInfo.getApiVersion();
        }

        public String androidVersion() {
            return DeviceInfo.getAndroidVersion();
        }

        public int androidSdk() {
            return DeviceInfo.getAndroidSdk();
        }

        public long sdTotalBytes() {
            return DeviceInfo.getSdTotalBytes(getRootDir());
        }

        public long sdUsedBytes() {
            return DeviceInfo.getSdUsedBytes(getRootDir());
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ===== 有序退出（铁律） =====

    private void beginOrderlyExit() {
        beginOrderlyExit("正在退出···");
    }

    /** exitingMsg：全屏退出弹窗文字（手动退出="正在退出···"；自动退出=传输完成文案） */
    private void beginOrderlyExit(String exitingMsg) {
        if (shuttingDown) return;
        AppLog.w("Exit", "开始有序退出：" + exitingMsg + "（方式=" + savedMode + "）");
        shuttingDown = true;
        isDisablingForFinish = true;
        stationWaiting = false;
        phase = PH_CLOSING;
        removeRadioCallbacks();
        try {
            unregisterReceiver(receiver);
        } catch (Throwable t) {
        }
        // 退出指示改用全屏弹窗样式（无按钮），不再回主界面显示状态文字，
        // 也不显示"已关闭"；文字随退出来源切换
        exitMsgView.setText(exitingMsg);
        showScreen(DLG_EXITING);
        new Thread(new Runnable() {
            public void run() {
                shutdownServicesAndRadio(savedMode);
                // 终局加固：收尾完成时热点若仍未确认沉降，再尽力补拆一轮
                // （不带等待——命令必须在这最后一刻递出去）
                if (MODE_HOTSPOT.equals(savedMode)) {
                    try {
                        if (!radiosConfirmedDown()) {
                            radio.removeGroup();
                            radio.issueDirectOff();
                            wifiManager.setWifiEnabled(false);
                        }
                    } catch (Throwable t) {
                    }
                }
                handler.post(new Runnable() {
                    public void run() {
                        // 不显示"已关闭"：收尾完成后直接通知相机结束应用
                        daFinishOnly();
                    }
                });
            }
        }, "ConnectShutdown").start();
    }

    /**
     * 有序关闭序列（退出/切换方式/清除记录共用）。固定顺序：
     * ① 停缩略图预取线程（立即释放 SD 卡文件句柄）
     * ② 停 PTP/IP（有序拆数据连接 → 协议/事件连接 → 监听 → 令牌表）
     * ③ [热点模式] 同步关 Direct → 关 WiFi → 轮询确认沉降（≤10s）
     *    [Wi-Fi 模式] 若 Wi-Fi 是本次会话由我们打开的，关回并确认沉降（铁律归位）；
     *    原本就开着的不碰用户网络
     * ④ 清除历史调试痕迹文件（必须在完全退出前完成）
     * ⑤ 恢复自动关机。
     * 绝不先杀进程。
     */
    void shutdownServicesAndRadio(String mode) {
        AppLog.i("Exit", "收尾：停服务 + 无线电归位（方式=" + mode + "）");
        stopPtpServer();

        if (MODE_HOTSPOT.equals(mode)) {
            // 先发命令再确认（省一轮查询往返）：拆组 → 关功能 → 关 WiFi，
            // 3 秒内未确认沉降再来一轮，总截止 15 秒。
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (true) {
                try {
                    radio.removeGroup();
                } catch (Throwable t) {
                }
                try {
                    radio.issueDirectOff();
                } catch (Throwable t) {
                }
                try {
                    wifiManager.setWifiEnabled(false);
                } catch (Throwable t) {
                }
                boolean down = false;
                long confirmEnd = SystemClock.elapsedRealtime() + 3000;
                while (SystemClock.elapsedRealtime() < confirmEnd) {
                    if (radiosConfirmedDown()) {
                        down = true;
                        break;
                    }
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (down || SystemClock.elapsedRealtime() >= deadline) break;
            }
            // 注销热点启动状态机的事件接收器（与 initAndStartWifiCycle 的注册
            // 对称）。此前切到 Wi-Fi 模式后它仍活着，Wi-Fi 开关一开就被
            // WIFI_STATE_ENABLED 唤醒去"使能 Direct/重建热点"——既报
            // "无法启用Direct模式"又把 WiFi 回滚关掉。
            try {
                unregisterReceiver(receiver);
            } catch (Throwable t) {
            }
        } else if (MODE_WIFI.equals(mode) && Boolean.TRUE.equals(wifiEnabledByUs)) {
            try {
                wifiManager.setWifiEnabled(false);
            } catch (Throwable t) {
            }
            long deadline = SystemClock.elapsedRealtime() + EXIT_CONFIRM_CAP_MS;
            while (SystemClock.elapsedRealtime() < deadline) {
                boolean down;
                try {
                    down = wifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED;
                } catch (Throwable t) {
                    down = true;
                }
                if (down) break;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        // 历史调试痕迹（日志文件）清除——完全退出前的最后一步
        deleteTraceFiles();
        restoreAutoPowerOff();
    }

    /**
     * 历届版本在**存储卡根目录**写过的文件名，逐个点名删除。
     *
     * <p>为什么这件事必须做干净：相机把"卡根出现陌生文件"当成图像数据库失同步，
     * 下次开机会挂出"正在修复数据"。本 App 正常运行时**不写卡**（配对表、
     * device_id.hex、prefs 全在应用私有目录，卡上只做读），所以卡上只可能留历届
     * 调试版本的日志 —— 把它们清掉，卡根就是干净的。
     *
     * <p>名单来自各版本 devlog：v1.0 期的崩溃日志与飞行记录仪、v1.1 起的单文件日志。
     */
    private static final String[] TRACE_NAMES = {
            "SONYCONNECT_LOG.TXT",      // v1.1 起（devlog 2026-09-06 第四轮）
            "SonyConnect-trace.log",    // v1.1 飞行记录仪（devlog 2026-09-06 第三轮）
            "SonyConnect-crash.log",    // v1.0 崩溃日志（devlog 2026-09-06 第二轮）
    };

    /** 调试痕迹清除：历届版本写过的日志文件全部删除（尽力而为，绝不抛） */
    static void deleteTraceFiles(Context c) {
        deleteTraceFiles(c, null);
    }

    /**
     * @param extraRoot 额外扫一个根（实例版传 getRootDir()；退出链的静态兜底路径传 null）
     */
    static void deleteTraceFiles(Context c, File extraRoot) {
        // ★ 这些路径多半是同一个卷的别名（/sdcard 就是符号链接），按 canonical path
        //   去重，别把同一个目录扫上三遍。
        File[] roots = new File[]{
                new File("/sdcard"),
                new File("/android/storage/sdcard0"),
                new File("/android/mnt/sdcard"),
                extraRoot,
        };
        java.util.HashSet<String> swept = new java.util.HashSet<String>();
        for (int i = 0; i < roots.length; i++) {
            File root = roots[i];
            if (root == null) continue;
            String key;
            try {
                key = root.getCanonicalPath();
            } catch (Throwable t) {
                key = root.getAbsolutePath();
            }
            if (!swept.add(key)) continue;
            if (!root.isDirectory()) continue;
            // ① 点名删（路径确定；扫不到也不算错）
            for (int j = 0; j < TRACE_NAMES.length; j++) {
                deleteQuietly(new File(root, TRACE_NAMES[j]));
            }
            // ② 扫一遍兜底：历届名字未必都记进了名单。**只看根目录这一层、只删文件**，
            //    所以 DCIM 目录与用户的照片永远不可能被误伤。
            File[] found;
            try {
                found = root.listFiles();
            } catch (Throwable t) {
                found = null;
            }
            if (found == null) continue;
            for (int j = 0; j < found.length; j++) {
                File f = found[j];
                String n = f.getName();
                if (n == null) continue;
                if (n.toLowerCase(java.util.Locale.US).indexOf("sonyconnect") < 0) continue;
                deleteQuietly(f);
            }
        }
        try {
            File priv = new File(c.getFilesDir(), "sonyconnect_log.txt");
            if (priv.isFile()) priv.delete();
        } catch (Throwable t) {
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.isFile()) f.delete();
        } catch (Throwable t) {
        }
    }

    private void deleteTraceFiles() {
        deleteTraceFiles(getApplicationContext(), getRootDir());
    }

    private boolean radiosConfirmedDown() {
        boolean wifiDown;
        try {
            wifiDown = wifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED;
        } catch (Throwable t) {
            wifiDown = false;
        }
        // 组壳存活用标准 API 查（isDirectEnabled 的扩展查询在本固件会超时误报"已关"）
        return wifiDown && radio.getLiveGroup() == null;
    }

    /**
     * 直接关机的竞速收尾（官方把清理挂在 onPause 里同步做的机制）。
     * 毫秒级关键步骤全部同步执行完才放行；无线沉降确认是慢环节，丢给后台
     * 线程尽力补，进程若先被杀则自然终止。
     */
    private void runForcedExitCritical() {
        shuttingDown = true;
        isDisablingForFinish = true;
        stationWaiting = false;
        phase = PH_CLOSING;
        removeRadioCallbacks();
        try {
            unregisterReceiver(receiver);
        } catch (Throwable t) {
        }

        stopPtpServer();
        restoreAutoPowerOff();
        if (MODE_HOTSPOT.equals(savedMode)) {
            try {
                wifiManager.setWifiEnabled(false);
            } catch (Throwable t) {
            }
            try {
                radio.issueDirectOff();
            } catch (Throwable t) {
            }
        } else if (MODE_WIFI.equals(savedMode) && Boolean.TRUE.equals(wifiEnabledByUs)) {
            try {
                wifiManager.setWifiEnabled(false);
            } catch (Throwable t) {
            }
        }
        deleteTraceFiles();
        daFinishOnly();

        if (MODE_HOTSPOT.equals(savedMode)) {
            new Thread(new Runnable() {
                public void run() {
                    long deadline = SystemClock.elapsedRealtime() + EXIT_CONFIRM_CAP_MS;
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (radiosConfirmedDown()) break;
                        // 可能还没沉下去（命令与驱动切换有时差）：补发后再等
                        try {
                            wifiManager.setWifiEnabled(false);
                        } catch (Throwable t) {
                        }
                        try {
                            radio.removeGroup();
                        } catch (Throwable t) {
                        }
                        try {
                            radio.issueDirectOff();
                        } catch (Throwable t) {
                        }
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    restoreAutoPowerOff();
                }
            }, "ConnectShutdown").start();
        }
    }

    private void daFinishOnly() {
        // 官方退出只有这一句：整个反编译树里找不到任何 finishComp 调用
        try {
            DAConnectionManager mgr = new DAConnectionManager(getApplicationContext());
            mgr.finish();
        } catch (Throwable t) {
        }
    }

    /** ExitCompletedReceiver 兜底：静态入口，Activity 可能已死 */
    static void killServicesQuietly() {
        MainActivity m = sInstance;
        if (m != null) {
            m.stopPtpServer();
        }
    }

    static void killRadioQuietly(Context context) {
        try {
            com.bi2qfa.sonyconnect.radio.RadioWrapperFactory.getInstance(context)
                    .issueDirectOff();
        } catch (Throwable t) {
        }
    }

    // ===== 官方会话协议 =====

    /** 每次 onResume 上报 AppInfo（参数与官方逐项一致，退出后才回取景界面） */
    private void notifyAppInfo() {
        try {
            Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
            intent.putExtra("package_name", getComponentName().getPackageName());
            intent.putExtra("class_name", getComponentName().getClassName());
            intent.putExtra("large_category", "CATEGORY_REC");
            intent.putExtra("small_category", "STILL");
            intent.putExtra("pullingback_key", PULLING_BACK_KEYS_FOR_PLAYBACK);
            intent.putExtra("resume_key", RESUME_KEYS_FOR_SHOOTING);
            sendBroadcast(intent);
        } catch (Throwable t) {
        }
    }

    // ===== 自动关机接管 =====

    private boolean autoPowerOffDisabled = false;

    private void disableAutoPowerOff() {
        if (autoPowerOffDisabled) return;
        sendBroadcast(buildApoIntent("APO/NO"));
        autoPowerOffDisabled = true;
    }

    private void restoreAutoPowerOff() {
        if (!autoPowerOffDisabled) return;
        sendBroadcast(buildApoIntent("APO/NORMAL"));
        autoPowerOffDisabled = false;
    }

    private Intent buildApoIntent(String mode) {
        Intent intent = new Intent();
        intent.setAction("com.android.server.DAConnectionManagerService.apo");
        intent.putExtra("apo_info", mode);
        return intent;
    }

    // ===== 电量 =====

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            try {
                updateBattery(intent);
            } catch (Throwable t) {
            }
        }
    };

    // ===== 电量分档（照抄原厂 `BatteryIcon.checkBatteryLevel()`） =====
    //
    // ★ 用户指出"电池图标对电量的映射做得不准确"，去看原版怎么映射的 —— 原版就是这个类：
    //   `com.sony.imaging.app.base.common.widget.BatteryIcon`（同步到智能手机的 APK 里，
    //   smali `checkBatteryLevel(I)I` 反出来）：
    //
    //       int level;
    //       if (value < 1)                  level = 0;      // 0 及"读不到" → 空图
    //       else if (batteryList[2] >= value) level = 1;     //  1..20
    //       else if (batteryList[1] >= value) level = 2;     // 21..50
    //       else if (batteryList[0] >= value) level = 3;     // 51..80
    //       else                              level = 4;     // 81..100
    //
    //   三个阈值来自系统属性 `ui.battery.threshold.list`（int 数组），原厂代码里读不到时
    //   的兜底是 {80, 50, 20}。**我们之前用的 10/35/60/85 是自己编的**，所以档位一直对不上。
    //   档位图与原厂一致：level 0 = error 图（ScalarA 那套 `icon_battery` level-list 里
    //   level 0 指的就是 `..._buttery_error`），1..4 = buttery1..4。
    private static final int[] BATTERY_THRESHOLDS_FALLBACK = {80, 50, 20};
    private int[] batteryThresholds;

    private int[] batteryThresholds() {
        if (batteryThresholds == null) {
            int[] t = null;
            try {
                t = ScalarProperties.getIntArray("ui.battery.threshold.list");
            } catch (Throwable th) {
            }
            if (t == null || t.length < 3) {
                t = BATTERY_THRESHOLDS_FALLBACK;
                AppLog.i("Power", "电量阈值属性读不到 → 用原厂兜底 "
                        + t[0] + "/" + t[1] + "/" + t[2]);
            } else {
                AppLog.i("Power", "电量阈值（原厂属性 ui.battery.threshold.list）="
                        + t[0] + "/" + t[1] + "/" + t[2]);
            }
            batteryThresholds = t;
        }
        return batteryThresholds;
    }

    /** 原厂 `checkBatteryLevel()` 的逐行照抄（含"读不到电量(<1) 归 0 档"）。 */
    private int checkBatteryLevel(int value) {
        int[] t = batteryThresholds();
        if (value < 1) return 0;
        if (value <= t[2]) return 1;
        if (value <= t[1]) return 2;
        if (value <= t[0]) return 3;
        return 4;
    }

    /** 档位 → 图：0 档 = error 图，1..4 = bat_1..bat_4（与原厂 level-list 一致）。 */
    private static int batteryIconRes(int level) {
        switch (level) {
            case 1: return R.drawable.bat_1;
            case 2: return R.drawable.bat_2;
            case 3: return R.drawable.bat_3;
            case 4: return R.drawable.bat_4;
            default: return R.drawable.bat_err;
        }
    }

    private void updateBattery(Intent intent) {
        if (intent == null) return;
        try {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                batteryPct = level * 100 / scale;
            }
            // 原厂分档：<1 → 0 档（空/error 图）；1..20 → 1；21..50 → 2；51..80 → 3；81+ → 4
            int grade = checkBatteryLevel(batteryPct);
            int icon = batteryIconRes(grade);
            String pct = batteryPct >= 0 ? batteryPct + "%" : "--";
            if (batteryPct != lastLoggedBattery) {
                lastLoggedBattery = batteryPct;
                AppLog.i("Power", "电量 " + pct + " → 第 " + grade + " 档（" + icon + "）");
            }
            for (int i = 0; i < HEADER_IDS.length; i++) {
                View h = findViewById(HEADER_IDS[i]);
                if (h == null) continue;
                ImageView bi = (ImageView) h.findViewById(R.id.battery_icon);
                TextView bt = (TextView) h.findViewById(R.id.battery_text);
                if (bi != null) bi.setImageResource(icon);
                if (bt != null) bt.setText(pct);
            }
        } catch (Throwable t) {
        }
    }

    // ===== 主页面状态显示 =====

    private void updateMainStatus() {
        lastMainStatus = buildMainStatusText();
        updateStatusTexts();
        updateHotspotTexts();
        refreshScreens();
        refreshWifiIcon();
        refreshCameraIcon();
    }

    /** 服务状态正文：两屏各一块（主界面的"服务状态"块、配对页的"服务状态"块），同一份文本。 */
    private void updateStatusTexts() {
        setTextIfChanged(statusView, lastMainStatus);
        setTextIfChanged(pairingStatus, lastMainStatus);
    }

    /**
     * 热点信息块的两行：热点名称与密码（主界面 / 配对页各一份，同一份数据）。
     *
     * <p>值还没拿到（启动中）时写"—"而不是留空：空白会让人以为这一块坏了。
     * 名称/密码只在热点模式下才有，Wi-Fi 模式下这一块根本不显示（键一是"Wi-Fi 设置"）。
     */
    private void updateHotspotTexts() {
        String name = "热点名称：" + (ssid != null && ssid.length() > 0 ? ssid : "—");
        String pass = "热点密码：" + (password != null && password.length() > 0 ? password : "—");
        setTextIfChanged(mainHotName, name);
        setTextIfChanged(mainHotPass, pass);
        setTextIfChanged(pairingHotName, name);
        setTextIfChanged(pairingHotPass, pass);
    }

    /**
     * 顶栏 Wi-Fi 指示（原厂素材 ic_wifi_header 48×32，位置照原厂放在电量左边）。
     *
     * <p>判据只有一条：**连接已就绪** —— {@code phase == PH_RUNNING}。热点模式是
     * Direct 组建好、Wi-Fi 模式是关联上并拿到 DHCP IP。启动中 / 失败 / 正在关闭
     * 三种状态都**不显示**：那几种情况下"有 Wi-Fi"不是真的，尤其失败态若亮着它，
     * 会把"没连上"说成"连上了"。
     *
     * <p>可见性只在变化时才 set（相机 CPU 弱，每秒一次的轮询不能白白重绘）。
     */
    private void refreshWifiIcon() {
        boolean ready = phase == PH_RUNNING;
        for (int i = 0; i < HEADER_IDS.length; i++) {
            View h = findViewById(HEADER_IDS[i]);
            if (h == null) continue;
            View w = h.findViewById(R.id.wifi_icon);
            if (w == null) continue;
            int want = ready ? View.VISIBLE : View.GONE;
            if (w.getVisibility() != want) w.setVisibility(want);
        }
    }

    /**
     * 主界面左边那台**相机图标**：无线电就绪之前是暗的。
     *
     * <p>用户要求：热点模式下左边的相机图标要等热点**就绪**（也就是顶栏 wi-fi 图标
     * 亮起）时再亮。判据因此与 {@link #refreshWifiIcon()} 是**同一条**
     * （{@code phase == PH_RUNNING}），并且由同一个 1s 心跳一起刷 —— 两处各写一套判据，
     * 迟早出现"wi-fi 亮了、相机还暗着"这种自相矛盾的画面。
     *
     * <p>暗图沿用原厂那套做法：同一张画把 alpha 压到 85/255（与
     * {@code ic_sync_phone_on/off}、{@code ic_sync_link_on/off} 同一规矩），
     * 不是另画一张 —— 原厂素材本身就是这么配对的。
     *
     * <p>只在变化时 setImageResource（相机 CPU 弱，每秒一次的轮询不能白白重绘）。
     */
    private void refreshCameraIcon() {
        if (mainIconLeft == null) {
            return;
        }
        int want = (phase == PH_RUNNING)
                ? R.drawable.ic_sync_camera : R.drawable.ic_sync_camera_off;
        if (want != mainCameraIconRes) {
            mainCameraIconRes = want;
            mainIconLeft.setImageResource(want);
        }
    }

    // ===== 主界面与服务状态块（两屏共用，见字段区那段 ★）=====

    /**
     * 两屏刷新（主界面 + 配对页）：左键文案、主界面两个大图标下的说明、三块正文显隐、
     * 热点名/密码、服务状态文本、二维码。
     *
     * <p>文案规则是用户定的：
     * <ul>
     *   <li>左键（主功能）随连接方式变：热点→<b>热点信息</b>（有热点才谈得上名称与密码）；
     *       接入点→<b>Wi-Fi 设置</b>（已经连着 Wi-Fi，生成不出热点二维码）；
     *       未设置→<b>连接方式</b></li>
     *   <li>左图标下：未设置→"连接方式"；热点→"相机热点"；接入点→"Wi-Fi已连接/未连接"</li>
     *   <li>右图标下：手机型号，没有手机连着就是"未连接"</li>
     * </ul>
     *
     * <p>图标也随连接状态换（原厂就是这个做法：`smartphone_state_image` 在 on/off 两张
     * 图之间切、`link_line` 在 connect_on/off 之间切）。off 那两张的 alpha 只有 85/255，
     * 显示出来是**暗的** —— 这不是素材坏了，原厂"没连上"就是灰的。
     */
    private void refreshScreens() {
        if (btnMainLeft == null) {
            return;
        }
        // 键一（主功能）的文案随连接方式变 —— 主界面与配对页**同一份判据、同一个方法**，
        // 两屏上那两颗按钮永远写着同样的字。
        String leftLabel = leftButtonLabel();
        setTextIfChanged(btnMainLeft, leftLabel);
        setTextIfChanged(btnPairingLeft, leftLabel);
        setTextIfChanged(btnPairingRight, "服务状态");

        String capLeft = "连接方式";
        if (MODE_HOTSPOT.equals(savedMode)) {
            capLeft = "相机热点";
        } else if (MODE_WIFI.equals(savedMode)) {
            String live = stationSsid();
            capLeft = (live != null && live.length() > 0) ? "Wi-Fi已连接" : "Wi-Fi未连接";
        }
        setTextIfChanged(mainCapLeft, capLeft);
        setTextIfChanged(mainCapRight, phoneModelText());

        // 手机图标随连接状态换 on/off（原厂 `smartphone_state_image` 就是这个做法）。
        // ★ 连线点阵**不换**：原厂 connect_on（4 点 + 箭头）是"传送中"页用的，
        //   主屏始终是 connect_off 那 6 个暗点 —— 用户给的原版照片里，手机已连上
        //   （名字都显示了）时中间依然是 6 个点。off 两张的 alpha 只有 85/255，
        //   显示出来是暗的，这不是素材坏了。
        boolean linked = ptpServer != null && ptpServer.connectedClientCount() > 0;
        int icon = linked ? R.drawable.ic_sync_phone_on : R.drawable.ic_sync_phone_off;
        if (icon != mainPhoneIconRes) {   // setImageResource 每次都会重画，状态没变就别碰
            mainPhoneIconRes = icon;
            mainIconPhone.setImageResource(icon);
            // 六个点也跟着亮起来（用户要求）。用的是原厂 connect_off 的**同一张几何**，
            // 只把 alpha 拉满做成的亮点版 —— 原厂那张 connect_on 是"4 点 + 箭头"，
            // 属于"传送中"的语义，不是"连接上了"。
            mainIconDots.setImageResource(
                    linked ? R.drawable.ic_sync_link_on : R.drawable.ic_sync_link_off);
        }

        // 两屏的三块正文显隐（第 0 块各是各的：主界面是连接示意，配对页是配对码）
        mainBlockDiagram.setVisibility(mainBlock == 0 ? View.VISIBLE : View.GONE);
        mainBlockHotspot.setVisibility(mainBlock == 1 ? View.VISIBLE : View.GONE);
        mainBlockStatus.setVisibility(mainBlock == 2 ? View.VISIBLE : View.GONE);
        pairingBlockCode.setVisibility(pairingBlock == 0 ? View.VISIBLE : View.GONE);
        pairingBlockHotspot.setVisibility(pairingBlock == 1 ? View.VISIBLE : View.GONE);
        pairingStatus.setVisibility(pairingBlock == 2 ? View.VISIBLE : View.GONE);

        updateStatusTexts();
        updateHotspotTexts();
        updateQrCode();
    }

    /**
     * 键一（主功能）的文案（用户定版）：
     * 热点→**热点信息**（热点名/密码 + 二维码，一屏给全）、接入点→**Wi-Fi 设置**、
     * 未设置→**连接方式**（此时连接方式还没选，键一就是"去选连接方式"）。
     */
    private String leftButtonLabel() {
        return MODE_HOTSPOT.equals(savedMode) ? "热点信息"
                : MODE_WIFI.equals(savedMode) ? "Wi-Fi 设置" : "连接方式";
    }

    /** 右图标下的文字：连接中的手机型号；没有会话就是"未连接"。 */
    private String phoneModelText() {
        if (ptpServer == null || ptpServer.connectedClientCount() <= 0) {
            return "未连接";
        }
        String name = ptpServer.connectedClientName();
        return (name != null && name.length() > 0) ? name : "已连接";
    }

    /**
     * 两屏常驻按钮行的统一处理（主界面与配对页那两颗按钮语义完全相同）。
     *
     * <p>键一（左）是"主功能"，随连接方式变：
     * <ul>
     *   <li>热点模式 → **热点信息**（热点名/密码在左、二维码在右；再按一次回到原块）；</li>
     *   <li>接入点模式 → 跳系统 Wi-Fi 设置；</li>
     *   <li>**还没选连接方式 → 铁律：一律弹连接方式选择**（把连接设置面板打开）。
     *       这一态下别的什么都做不了 —— 没有连接方式就没有服务，热点信息与二维码
     *       也无从谈起，所以键一在这儿就是"去选连接方式"。</li>
     * </ul>
     * 键二（右）恒为服务状态，再按一次回到第 0 块。
     * 启动失败时两键都当"重试"用（与旧版一致：主界面 ENTER = 重试）。
     */
    private void onBlockButton(int which) {
        AppLog.i("UI", screenName(screen) + " 按钮 " + (which == 0 ? "左" : "右")
                + "（phase=" + phase + "，块=" + (screen == SCR_PAIRING ? pairingBlock : mainBlock) + "）");
        if (phase == PH_FAILED) {
            retryFromFailure();
            return;
        }
        // 当前屏的正文块号（两屏各记一份，互不干扰）
        int block = (screen == SCR_PAIRING) ? pairingBlock : mainBlock;
        if (which == 1) {
            block = (block == 2) ? 0 : 2;        // 服务状态：再按回到原块
        } else if (MODE_HOTSPOT.equals(savedMode)) {
            block = (block == 1) ? 0 : 1;        // 热点信息：再按一次收起
        } else if (MODE_WIFI.equals(savedMode)) {
            jumpToWifiSettings();
            return;
        } else {
            // 没有连接方式：弹连接设置（它就是"选择连接方式"那一屏）。
            // modeFromMenu = false：这不是从选项菜单进来的，选完之后该走
            // "首启/没设备"那条落地判据（见 finishModeChoice）。
            modeFromMenu = false;
            modeFromPairing = false;
            showScreen(SCR_MODE);
            return;
        }
        if (screen == SCR_PAIRING) {
            pairingBlock = block;
        } else {
            mainBlock = block;
        }
        refreshScreens();
    }

    private void setTextIfChanged(TextView v, String s) {
        if (v != null && s != null && !s.equals(v.getText().toString())) {
            v.setText(s);
        }
    }

    /** 底部按键引导用图标字体（原厂引导不是图片，是图标字体的私有区字形）。 */
    private void applyIconFont(int... ids) {
        android.graphics.Typeface tf;
        try {
            tf = android.graphics.Typeface.createFromAsset(getAssets(), "fonts/icons.ttf");
        } catch (Throwable t) {
            return;   // 字体缺失就退回系统字面（会显示成方框，但不会崩）
        }
        for (int i = 0; i < ids.length; i++) {
            TextView v = (TextView) findViewById(ids[i]);
            if (v != null) {
                v.setTypeface(tf);
            }
        }
    }

    /**
     * 服务状态正文（ticker 与各事件路径共用；文本相等即无需重画）。
     *
     * <p>★ 第一行是"状态"行，**处于配对模式时在后面加一个括号**（用户定版）：
     * {@code 状态：运行中（配对模式）}。"处于配对模式"的判据是**配对窗口开着**
     * ——那才是"正在等手机输码"这一事实（离开配对页就关窗，见 leavePairingMode），
     * 而不是"当前画的是配对页"这种界面状态。所以这一行在启动中/运行中/失败/关闭
     * 各态都可能带上它：配对页最常见的时刻反而是服务还没起来的"启动中…"。
     */
    private String buildMainStatusText() {
        StringBuilder sb = new StringBuilder();
        switch (phase) {
            case PH_RUNNING:
                sb.append("状态：运行中");
                break;
            case PH_FAILED:
                sb.append("状态：启动失败");
                break;
            case PH_CLOSING:
                sb.append(modeSwitching ? "状态：正在切换…" : "状态：正在关闭…");
                break;
            default:
                sb.append("状态：启动中…");
                break;
        }
        if (inPairingMode()) {
            sb.append("（配对模式）");
        }
        sb.append('\n');
        switch (phase) {
            case PH_RUNNING:
                sb.append("手机端：").append(phoneStatusText()).append('\n');
                sb.append("连接方式：").append(MODE_HOTSPOT.equals(savedMode) ? "相机热点" : "Wi-Fi 客户端").append('\n');
                if (MODE_HOTSPOT.equals(savedMode)) {
                    sb.append("热点名称：").append(ssid != null ? ssid : "—").append('\n');
                    sb.append("热点密码：").append(password != null ? password : "—");
                } else {
                    // Wi-Fi 名实时读（关联刚完成时一次读取可能拿不到，重读补上）
                    String live = stationSsid();
                    if (live == null || live.length() == 0) live = ssid;
                    sb.append("Wi-Fi：").append(live != null ? live : "—");
                }
                break;
            case PH_FAILED:
                sb.append(errorMsg != null ? errorMsg : "").append('\n');
                sb.append("[确定键] 重试");
                break;
            default:
                // 启动中：只留"正在做什么"。原先那句"修改Wi-Fi设置：MENU → 连接设置 →
                // Wi-Fi 设置"指的是连接设置里已经被删掉的那一行（用户要求删掉这类提示）。
                if (MODE_WIFI.equals(savedMode) && phase == PH_STARTING) {
                    sb.append("正在连接 Wi-Fi 网络");
                }
                break;
        }
        return sb.toString();
    }

    /**
     * 是否处于**配对模式**：配对窗口正开着（6 位码还在有效期里、等着手机来输）。
     *
     * <p>窗口只在配对页上开着（进页开窗、离开关窗），所以它与"正在配对页"几乎等价，
     * 但它表达的是**事实**而不是界面状态：窗口过期时 updatePairingScreen 会立刻换一张
     * 新码，那一刻状态行的括号不该闪断。
     */
    private boolean inPairingMode() {
        return pairingStore != null && pairingStore.isOpen(System.currentTimeMillis());
    }

    /**
     * 主屏"手机端已连接/未连接"。
     *
     * 依据是相机端**当前有没有已授权的控制会话**，不是"配对表里有几台" ——
     * 配对是历史，连接是当下，两者不能混（配对表非空但没人在线是很常见的状态）。
     */
    private String phoneStatusText() {
        if (ptpServer == null || ptpServer.connectedClientCount() <= 0) {
            return "未连接";
        }
        String name = ptpServer.connectedClientName();
        if (name == null || name.length() == 0) {
            return "已连接";
        }
        return "已连接（" + name + "）";
    }

    /**
     * 解除配对后通知对方（只有对方正连着时才送得到）。
     *
     * <p>手机端收到 {@code EV_PAIRED_REMOVED} 会清掉本地记录并断开当前会话 ——
     * 这就是"已连接时解除配对必须同步到对方"的送达路径。对方不在线时送不到：那时
     * 手机端还留着记录，下次连接会被 {@code Init Fail(NOT_PAIRED)} 挡回来，手机端
     * 据此自动清本地记录（"未连接时单方解除"的兜底路径，见协议文档）。
     */
    private void notifyPeerUnpaired(String id) {
        if (ptpServer == null || id == null) {
            return;
        }
        String connected = ptpServer.connectedClientIdHex();
        if (connected != null && connected.equalsIgnoreCase(id)) {
            ptpServer.pushPairRemoved();
        }
    }

    /**
     * 热点二维码：**主界面与配对页各一份，同一份内容**（都是扫了就连上相机热点）。
     *
     * <p>仅热点模式的运行态画得出来（接入点模式没有可扫的热点，启动期也还没有
     * SSID/密码）—— 两屏同一条判据，所以"主界面有码、配对页没有"这种情况不会发生。
     */
    private void updateQrCode() {
        boolean canDraw = phase == PH_RUNNING && MODE_HOTSPOT.equals(savedMode)
                && ssid != null && password != null;
        Bitmap bmp = null;
        if (canDraw) {
            String wifi = "WIFI:T:WPA;S:" + ssid + ";P:" + password + ";;";
            bmp = renderQr(QrCode.encode(wifi), 6, 8, 4);
        }
        setQrView(qrView, qrCaption, bmp, canDraw);
        setQrView(pairingQrView, pairingQrCaption, bmp, canDraw);
    }

    private void setQrView(ImageView view, TextView caption, Bitmap bmp, boolean visible) {
        if (view == null) {
            return;
        }
        view.setImageBitmap(bmp);
        // 用 INVISIBLE 不用 GONE：二维码不显示时正文块本身还在，
        // 留白比让"扫码加入热点"这行字跳到顶上好看（用户反馈过残留提示）
        view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        if (caption != null) {
            caption.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }


    // ===== QR 渲染（反挤压：模块 6x8，静区 4） =====

    private Bitmap renderQr(boolean[][] modules, int scaleX, int scaleY, int quiet) {
        int n = modules.length;
        int width = (n + 2 * quiet) * scaleX;
        int height = (n + 2 * quiet) * scaleY;
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFFFFFFFF;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (!modules[r][c]) continue;
                for (int dy = 0; dy < scaleY; dy++) {
                    int y = (quiet + r) * scaleY + dy;
                    for (int dx = 0; dx < scaleX; dx++) {
                        int x = (quiet + c) * scaleX + dx;
                        pixels[y * width + x] = 0xFF000000;
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    // ===== 根目录（SDK 分流 + Environment 兜底） =====

    private File getRootDir() {
        String preferred = Build.VERSION.SDK_INT <= 10
                ? "/android/mnt/sdcard"
                : "/android/storage/sdcard0";
        File f = new File(preferred);
        if (f.exists() && f.isDirectory()) return f;
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null && ext.exists() && ext.isDirectory()) return ext;
        return f;
    }

    // ===== 列表行适配器 =====

    private abstract class FocusTracker implements AdapterView.OnItemSelectedListener {
        private final BaseAdapter adapter;

        FocusTracker(BaseAdapter adapter) {
            this.adapter = adapter;
        }

        abstract void onNewFocus(int pos);

        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            onNewFocus(position);
            adapter.notifyDataSetChanged();
        }

        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    private abstract class RowAdapter extends BaseAdapter {
        private String[][] items;
        /** 行布局：页码菜单/设置菜单/已配对设备都是 49px 的 row_page49|row_set49 */
        private int rowLayout;
        /** 选中条：橙实心条 row_focus（49px 行）。原厂 82px 行是另一种语义
         *  （发光描边框），本 App 现在没有 82px 行了，那套素材已删。 */
        private int focusDrawable;

        RowAdapter(String[][] items, int rowLayout, int focusDrawable) {
            this.items = items;
            this.rowLayout = rowLayout;
            this.focusDrawable = focusDrawable;
        }

        /** 换一份行模型（菜单在"主界面版"与"配对模式版"之间切换时用）。 */
        void setItems(String[][] next) {
            if (next != null) {
                items = next;
                notifyDataSetChanged();
            }
        }

        protected abstract int focusPos();

        protected abstract void setFocusPos(int pos);

        /** 设置菜单那种单选行：位置 pos 是否画实心圆点（只有 mode_list 会覆写）。 */
        protected boolean radioOn(int pos) {
            return false;
        }

        public int getCount() {
            return items.length;
        }

        public Object getItem(int position) {
            return items[position];
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(rowLayout, parent, false);
            }
            TextView title = (TextView) v.findViewById(R.id.element_text);
            if (title != null) {
                title.setText(items[position][0]);
            }
            TextView value = (TextView) v.findViewById(R.id.value_text);
            if (value != null) {
                String val = items[position][1];
                value.setVisibility(val.length() == 0 ? View.INVISIBLE : View.VISIBLE);
                value.setText(val);
            }
            // 前导单选圆点（只有设置菜单的行有这一格）
            ImageView radio = (ImageView) v.findViewById(R.id.row_radio);
            if (radio != null) {
                radio.setImageResource(radioOn(position)
                        ? R.drawable.radio_on : R.drawable.radio_off);
            }
            // 选中图案画在**行内的一个 ImageView 图层**上（原厂 tag=selectable_background，
            // src=menu_list_selector），不碰行背景 —— 原厂就是为了避开"9-patch 的隐式内边距
            // 被宿主 View 继承"，用 background 装 9-patch 会把行内文字整体挤偏几个像素。
            boolean isFocused = position == focusPos();
            ImageView bg = (ImageView) v.findViewById(R.id.row_focus_bg);
            if (bg != null) {
                if (isFocused) {
                    bg.setImageResource(focusDrawable);
                } else {
                    bg.setImageDrawable(null);
                }
            } else {
                v.setBackgroundDrawable(isFocused
                        ? getResources().getDrawable(focusDrawable) : null);
            }
            return v;
        }
    }
}
