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
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.sony.scalar.sysutil.ScalarInput;

import java.io.File;
import java.io.IOException;
import java.util.List;

import com.bi2qfa.sonyconnect.radio.RadioWrapper;
import com.bi2qfa.sonyconnect.radio.RadioWrapperFactory;

public class MainActivity extends Activity {

    private static final int SCR_MAIN = 0;
    private static final int SCR_MENU = 1;
    private static final int SCR_MODE = 2;
    private static final int SCR_ABOUT = 3;
    private static final int DLG_CONFIRM = 4;
    private static final int DLG_DEFAULT = 5;
    private static final int DLG_EXITING = 6;
    private int screen = SCR_MAIN;

    private static final int CONFIRM_EXIT = 0;
    private static final int CONFIRM_WIPE = 1;
    private int pendingConfirm = CONFIRM_EXIT;

    private static final int PH_STARTING = 0;
    private static final int PH_RUNNING = 1;
    private static final int PH_FAILED = 2;
    private static final int PH_CLOSING = 3;
    private static final int PH_CLOSED = 4;
    private int phase = PH_STARTING;

    static final int FTP_PORT = 2121;
    static final int CONNECT_PORT = 2122;
    static final String HOTSPOT_IP = "192.168.122.1";

    private static final long DELAY_WIFI_ENABLE_MS = 2000;
    private static final long DELAY_FATAL_CHECK_MS = 5000;
    private static final long EXIT_CONFIRM_CAP_MS = 10000;
    private static final int STATION_POLLS_MAX = 120;

    static final String MODE_WIFI = "wifi";
    static final String MODE_HOTSPOT = "hotspot";
    private static final String PREFS = "connect";
    private static final String KEY_MODE = "mode";

    private SharedPreferences prefs;
    private final Handler handler = new Handler();

    private boolean shuttingDown = false;
    private boolean jumpingToSystem = false;
    private boolean startedOnce = false;

    private int menuFocus = 0;
    private int modeFocus = 0;
    private int confirmFocus = 0;
    private int defFocus = 0;

    private String pendingMode = null;

    private View screenMain, screenMenu, screenMode, screenAbout, dlgConfirm, dlgDefault, dlgExiting;
    private android.widget.ScrollView aboutScroll;
    private TextView statusView, batteryText, aboutText, confirmMsg, qrCaption, exitMsgView;
    private ImageView qrView;
    private TextView btnConfirmOk, btnConfirmCancel, btnDefaultOk, btnDefaultNo;
    private ListView menuList, modeList;
    private BatteryView batteryIcon;

    private int batteryPct = -1;

    private final String[][] menuItems = {
            {"连接设置", "未设置"},
            {"关于", ""},
            {"清除软件数据", ""},
            {"退出应用程序", ""},
    };
    private final String[][] modeItems = {
            {"连接到 Wi-Fi 网络", ""},
            {"使用相机热点", ""},
            {"Wi-Fi 设置", ""},
    };

    private String savedMode = null;

    private WifiManager wifiManager;
    private RadioWrapper radio;

    private FtpServer ftpServer;
    private ConnectServer connectServer;
    private ThumbPrefetcher thumbPrefetcher;

    private volatile String ssid = null;
    private volatile String password = null;
    private volatile String currentIp = null;
    private String errorMsg = null;

    private boolean isInitialDisabling;
    private boolean isDisableActionFiltered;
    private boolean isEnableActionFiltered;
    private boolean isGroupCreateActionFiltered;
    private boolean isDisablingForFinish;
    private boolean isRetrying;

    private boolean isDirectEnableRetrying;

    private int watchdogRecoveries = 0;

    private int curWifiMgrState = WifiManager.WIFI_STATE_ENABLED;
    private int curWifiDirectMgrState = RadioWrapper.DIRECT_STATE_UNKNOWN;

    private BroadcastReceiver receiver;
    private IntentFilter iFilter;

    private int stationPolls = 0;
    private int ssidRetries = 0;
    private boolean stationNudged = false;

    private Boolean wifiEnabledByUs = null;
    private boolean stationWaiting = false;

    private boolean modeSwitching = false;

    private boolean modeFromMenu = false;

    private static MainActivity sInstance;

    private static final String[] PULLING_BACK_KEYS_FOR_PLAYBACK = {
            "KEY_S2", "KEY_S1_1", "KEY_S1_2", "KEY_MOVREC", "KEY_MODE_DIAL", "KEY_USB_CONNECT"};
    private static final String[] RESUME_KEYS_FOR_SHOOTING = {
            "KEY_POWER_SLIDE_PON", "KEY_RELEASE_APO", "KEY_PLAY_APO", "KEY_MEDIA_INOUT_APO",
            "KEY_LENS_APO", "KEY_ACCESSORY_APO", "KEY_DEDICATED_APO", "KEY_POWER_APO", "KEY_PLAY_PON"};

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sInstance = this;

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        savedMode = prefs.getString(KEY_MODE, null);

        screenMain = findViewById(R.id.screen_main);
        screenMenu = findViewById(R.id.screen_menu);
        screenMode = findViewById(R.id.screen_mode);
        screenAbout = findViewById(R.id.screen_about);
        dlgConfirm = findViewById(R.id.dlg_exit);
        dlgDefault = findViewById(R.id.dlg_default);
        dlgExiting = findViewById(R.id.dlg_exiting);

        statusView = (TextView) findViewById(R.id.status_view);
        batteryText = (TextView) findViewById(R.id.battery_text);
        batteryIcon = (BatteryView) findViewById(R.id.battery_icon);
        aboutText = (TextView) findViewById(R.id.about_text);
        qrView = (ImageView) findViewById(R.id.qr_view);
        aboutScroll = (android.widget.ScrollView) findViewById(R.id.about_scroll);
        qrCaption = (TextView) findViewById(R.id.qr_caption);
        confirmMsg = (TextView) findViewById(R.id.exit_msg);
        exitMsgView = (TextView) findViewById(R.id.exit_ing_msg);

        btnConfirmOk = (TextView) findViewById(R.id.btn_exit_ok);
        btnConfirmCancel = (TextView) findViewById(R.id.btn_exit_cancel);
        btnDefaultOk = (TextView) findViewById(R.id.btn_default_ok);
        btnDefaultNo = (TextView) findViewById(R.id.btn_default_no);

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

        menuList = (ListView) findViewById(R.id.menu_list);
        modeList = (ListView) findViewById(R.id.mode_list);

        menuList.setAdapter(new RowAdapter(menuItems) {
            protected int focusPos() {
                return menuFocus;
            }

            protected void setFocusPos(int pos) {
                menuFocus = pos;
            }
        });
        modeList.setAdapter(new RowAdapter(modeItems) {
            protected int focusPos() {
                return modeFocus;
            }

            protected void setFocusPos(int pos) {
                modeFocus = pos;
            }
        });
        menuList.setItemsCanFocus(false);
        menuList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        modeList.setItemsCanFocus(false);
        modeList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

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

        try {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable t) {
        }
        updateBattery(registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));

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

    private abstract class FocusSink {
        abstract void onFocused();
    }

    private void setupDialogButton(final TextView b, final FocusSink sink) {
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) sink.onFocused();

                TextView tv = (TextView) v;
                if (hasFocus) {
                    tv.setBackgroundResource(R.drawable.row_bg_camera);
                    tv.setTextColor(0xffffffff);
                } else {
                    tv.setBackgroundResource(R.drawable.btn_dialog);
                    tv.setTextColor(0xffdddddd);
                }
                tv.invalidate();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        notifyAppInfo();
        jumpingToSystem = false;
        if (shuttingDown) return;
        if (isAirplaneModeOn()) {
            daFinishOnly();
            return;
        }
        if (savedMode == null) {

            modeFromMenu = false;
            showScreen(SCR_MODE);
            return;
        }
        if (!startedOnce) {
            startedOnce = true;
            applyMode();
            return;
        }
        showScreen(screen);
    }

    @Override
    protected void onPause() {

        if (!shuttingDown && !jumpingToSystem) {
            runForcedExitCritical();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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
        stopFtpServer();
        stopConnectServer();
        if (MODE_HOTSPOT.equals(savedMode)) {

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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (shuttingDown) {
            return true;
        }
        switch (event.getScanCode()) {
            case ScalarInput.ISV_KEY_MENU:
                onMenuKey();
                return true;
            case ScalarInput.ISV_KEY_ENTER:
                onEnterKey();
                return true;
            case ScalarInput.ISV_KEY_UP:
            case ScalarInput.ISV_KEY_DOWN:

                if (screen == SCR_ABOUT) {
                    aboutScroll.smoothScrollBy(0,
                            event.getScanCode() == ScalarInput.ISV_KEY_UP ? -120 : 120);
                    return true;
                }

                if (screen != DLG_CONFIRM && screen != DLG_DEFAULT) {
                    moveFocus(event.getScanCode() == ScalarInput.ISV_KEY_UP ? -1 : 1);
                }
                return true;
            case ScalarInput.ISV_KEY_LEFT:

                if (screen == DLG_CONFIRM) btnConfirmOk.requestFocus();
                else if (screen == DLG_DEFAULT) btnDefaultOk.requestFocus();
                return true;
            case ScalarInput.ISV_KEY_RIGHT:
                if (screen == DLG_CONFIRM) btnConfirmCancel.requestFocus();
                else if (screen == DLG_DEFAULT) btnDefaultNo.requestFocus();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void onMenuKey() {
        switch (screen) {
            case SCR_MAIN:
                showScreen(SCR_MENU);
                break;

            case SCR_MENU:
                showScreen(SCR_MAIN);
                break;
            case SCR_ABOUT:
                showScreen(SCR_MAIN);
                break;
            case SCR_MODE:

                if (modeFromMenu) {
                    showScreen(SCR_MAIN);
                } else {
                    showConfirm(CONFIRM_EXIT);
                }
                break;
            case DLG_CONFIRM:

                showScreen(SCR_MENU);
                break;
            case DLG_DEFAULT:

                showScreen(SCR_MODE);
                break;
            default:
                break;
        }
    }

    private void onEnterKey() {
        switch (screen) {
            case SCR_MAIN:
                if (phase == PH_FAILED) {
                    retryFromFailure();
                }
                break;
            case SCR_MENU:
                onMenuActivated(menuFocus);
                break;
            case SCR_MODE:
                onModeActivated(modeFocus);
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

    private void moveFocus(int delta) {
        switch (screen) {
            case SCR_MENU:
                moveListFocus(menuList, menuItems.length, delta);
                break;
            case SCR_MODE:
                moveListFocus(modeList, modeItems.length, delta);
                break;
            case DLG_CONFIRM:
                (confirmFocus == 0 ? btnConfirmCancel : btnConfirmOk).requestFocus();
                break;
            case DLG_DEFAULT:
                (defFocus == 0 ? btnDefaultNo : btnDefaultOk).requestFocus();
                break;
            default:
                break;
        }
    }

    private void moveListFocus(ListView list, int count, int delta) {
        RowAdapter adapter = (RowAdapter) list.getAdapter();
        if (count <= 0) return;
        int next = (adapter.focusPos() + delta + count) % count;
        adapter.setFocusPos(next);
        list.setSelection(next);
        adapter.notifyDataSetChanged();
    }

    private void showScreen(int target) {
        screen = target;
        screenMain.setVisibility(target == SCR_MAIN ? View.VISIBLE : View.GONE);
        screenMenu.setVisibility(target == SCR_MENU ? View.VISIBLE : View.GONE);
        screenMode.setVisibility(target == SCR_MODE ? View.VISIBLE : View.GONE);
        screenAbout.setVisibility(target == SCR_ABOUT ? View.VISIBLE : View.GONE);
        dlgConfirm.setVisibility(target == DLG_CONFIRM ? View.VISIBLE : View.GONE);
        dlgDefault.setVisibility(target == DLG_DEFAULT ? View.VISIBLE : View.GONE);
        dlgExiting.setVisibility(target == DLG_EXITING ? View.VISIBLE : View.GONE);

        if (target == SCR_MENU) {
            menuList.requestFocus();
            menuList.setSelection(((RowAdapter) menuList.getAdapter()).focusPos());
            ((BaseAdapter) menuList.getAdapter()).notifyDataSetChanged();
        } else if (target == SCR_MODE) {
            modeList.requestFocus();
            modeList.setSelection(((RowAdapter) modeList.getAdapter()).focusPos());
            ((BaseAdapter) modeList.getAdapter()).notifyDataSetChanged();
        } else if (target == SCR_ABOUT) {
            aboutScroll.scrollTo(0, 0);
            loadAboutAsync();
        } else if (target == DLG_CONFIRM) {
            confirmFocus = 0;
            btnConfirmOk.requestFocus();
        } else if (target == DLG_DEFAULT) {
            defFocus = 0;
            btnDefaultOk.requestFocus();
        }
    }

    private void onMenuActivated(int pos) {
        if (pos == 0) {
            modeFromMenu = true;
            showScreen(SCR_MODE);
        } else if (pos == 1) {
            showScreen(SCR_ABOUT);
        } else if (pos == 2) {
            showConfirm(CONFIRM_WIPE);
        } else if (pos == 3) {
            showConfirm(CONFIRM_EXIT);
        }
    }

    private void showConfirm(int what) {
        pendingConfirm = what;
        confirmMsg.setText(what == CONFIRM_EXIT
                ? "确定要退出 SonyConnect 吗？"
                : "清除连接方式记录并重新选择？");
        showScreen(DLG_CONFIRM);
    }

    private void runConfirmAction(boolean ok) {
        int what = pendingConfirm;
        showScreen(SCR_MAIN);
        if (!ok) return;
        if (what == CONFIRM_EXIT) {
            beginOrderlyExit();
        } else if (what == CONFIRM_WIPE) {
            wipeModeAndRechoose();
        }
    }

    private void onModeActivated(int pos) {
        if (pos == 0 || pos == 1) {
            pendingMode = pos == 0 ? MODE_WIFI : MODE_HOTSPOT;
            showScreen(DLG_DEFAULT);
        } else {

            jumpToWifiSettings();
        }
    }

    private void finishModeChoice(boolean asDefault) {
        if (pendingMode != null) {
            final String newMode = pendingMode;
            final boolean def = asDefault;
            pendingMode = null;
            if (newMode.equals(savedMode)) {

                if (def) prefs.edit().putString(KEY_MODE, savedMode).commit();
                refreshMenuValues();
                showScreen(SCR_MAIN);
                return;
            }
            beginModeSwitch(newMode, def);
            return;
        }
        showScreen(SCR_MAIN);
    }

    private void refreshMenuValues() {
        String v = MODE_WIFI.equals(savedMode) ? "Wi-Fi"
                : MODE_HOTSPOT.equals(savedMode) ? "热点" : "未设置";
        menuItems[0][1] = v;
    }

    private void applyMode() {
        refreshMenuValues();
        updateMainStatus();
        if (MODE_HOTSPOT.equals(savedMode)) {
            initAndStartWifiCycle(true);
        } else {
            startStationFlow();
        }
    }

    private void beginModeSwitch(final String newMode, final boolean asDefault) {
        final String oldMode = savedMode;
        shuttingDown = true;
        stationWaiting = false;
        modeSwitching = true;

        removeRadioCallbacks();
        phase = PH_CLOSING;
        showScreen(SCR_MAIN);
        updateMainStatus();
        new Thread("ModeSwitch") {
            public void run() {
                shutdownServicesAndRadio(oldMode);
                handler.post(new Runnable() {
                    public void run() {
                        shuttingDown = false;
                        modeSwitching = false;
                        savedMode = newMode;
                        if (asDefault) {
                            prefs.edit().putString(KEY_MODE, savedMode).commit();
                        }
                        applyMode();
                    }
                });
            }
        }.start();
    }

    private void wipeModeAndRechoose() {
        shuttingDown = true;
        stationWaiting = false;
        removeRadioCallbacks();
        phase = PH_CLOSING;
        showScreen(SCR_MAIN);
        updateMainStatus();
        new Thread("ConnectWipe") {
            public void run() {
                shutdownServicesAndRadio(savedMode);
                DeviceInfo.clearDiag(MainActivity.this);

                deleteTraceFiles();
                handler.post(new Runnable() {
                    public void run() {
                        shuttingDown = false;
                        startedOnce = false;
                        prefs.edit().remove(KEY_MODE).commit();
                        savedMode = null;
                        phase = PH_STARTING;
                        ssid = null;
                        password = null;
                        currentIp = null;
                        modeFromMenu = false;
                        refreshMenuValues();
                        showScreen(SCR_MODE);
                        updateMainStatus();
                    }
                });
            }
        }.start();
    }

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

    private void handleEvent(Intent intent) {

        if (shuttingDown || !MODE_HOTSPOT.equals(savedMode)) return;
        String action = intent.getAction();
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
        curWifiDirectMgrState = currentState;
        switch (currentState) {
            case RadioWrapper.DIRECT_STATE_DISABLED:
                if (previousState == RadioWrapper.DIRECT_STATE_ENABLED) return;
                if (isDisableActionFiltered) {
                } else if (isDisablingForFinish) {
                } else if (isInitialDisabling) {

                } else {
                    if (isRetrying) {
                    } else {
                        delayFatalCheck();
                    }
                }
                return;
            case RadioWrapper.DIRECT_STATE_ENABLED:
                isRetrying = false;
                isDirectEnableRetrying = false;
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
        errorMsg = reason;
        phase = PH_FAILED;
        handler.removeCallbacks(stalledStartupCheck);
        handler.removeCallbacks(delayedDirectEnableRetry);

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

    private void startStationFlow() {
        if (shuttingDown) return;
        phase = PH_STARTING;
        errorMsg = null;
        currentIp = null;
        updateMainStatus();

        removeRadioCallbacks();

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

            if (state != WifiManager.WIFI_STATE_ENABLED && stationPolls >= 16 && !stationNudged) {
                stationNudged = true;
                try {
                    wifiManager.setWifiEnabled(true);
                } catch (Throwable t) {
                }
            }

            String ip = state == WifiManager.WIFI_STATE_ENABLED ? stationIpAddress() : null;
            if (ip != null) {

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
                updateMainStatus();
                return;
            }
            if (stationPolls++ >= STATION_POLLS_MAX) {
                stationWaiting = false;
                phase = PH_FAILED;
                errorMsg = "未检测到 Wi-Fi 连接\n请检查网络，或 MENU→连接设置→Wi-Fi 设置 手动连接";
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

            if (s.length() == 0 || "<unknown ssid>".equals(s)) return null;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

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

        try {
            startActivity(new Intent("com.sony.scalar.app.wifisettings.WifiSettings"));
            return;
        } catch (Throwable t) {
        }

        try {
            Intent i = new Intent();
            i.setClassName("com.sony.scalar.app.wifisettings",
                    "com.sony.scalar.app.wifisettings.WifiSettingsActivity");
            startActivity(i);
            return;
        } catch (Throwable t) {
        }

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

    private synchronized boolean startServices() {
        if (ftpServer == null) {
            try {
                FtpServer s = new FtpServer(getRootDir(), FTP_PORT, null);
                s.setPayloadSource(new FtpServer.PayloadSource() {
                    public byte[] extractSmall(String relPath, File f) {
                        return thumbPrefetcher != null ? thumbPrefetcher.lookup(relPath) : null;
                    }
                });
                s.start();
                ftpServer = s;
            } catch (IOException e) {
                ftpServer = null;
                return false;
            }
        }
        if (thumbPrefetcher == null) {
            thumbPrefetcher = new ThumbPrefetcher(getRootDir());
        }
        if (connectServer == null) {
            try {
                connectServer = new ConnectServer(CONNECT_PORT, new ConnectHandler());
                connectServer.start();
            } catch (IOException e) {
                connectServer = null;
                stopFtpServer();
                return false;
            }
        }

        return true;
    }

    private void stopFtpServer() {
        if (ftpServer != null) {
            try {
                ftpServer.stop();
            } catch (Throwable t) {
            }
            ftpServer = null;
        }
    }

    private void stopConnectServer() {
        if (thumbPrefetcher != null) {
            try {
                thumbPrefetcher.stop();
            } catch (Throwable t) {
            }
            thumbPrefetcher = null;
        }
        if (connectServer != null) {
            try {
                connectServer.stop();
            } catch (Throwable t) {
            }
            connectServer = null;
        }
    }

    private class ConnectHandler implements ConnectServer.Handler {
        public String onHello(int proto) {
            if (proto != ConnectServer.PROTOCOL_VERSION) {
                return SJson.endObj(SJson.member(SJson.member(SJson.startObj(),
                        "error", "proto_mismatch"), "supported", ConnectServer.PROTOCOL_VERSION));
            }
            StringBuilder sb = SJson.startObj();
            SJson.member(sb, "rsp", "HELLO");
            SJson.member(sb, "proto", ConnectServer.PROTOCOL_VERSION);
            SJson.member(sb, "app", "SonyConnectCamera");
            SJson.member(sb, "mode", MODE_HOTSPOT.equals(savedMode) ? "hotspot" : "wifi");
            return SJson.endObj(sb);
        }

        public String onHeartbeat() {
            StringBuilder sb = SJson.startObj();
            SJson.member(sb, "rsp", "HEARTBEAT");
            SJson.member(sb, "app", "SonyConnectCamera");

            SJson.member(sb, "batteryPct", DeviceInfo.getBatteryPct(MainActivity.this));
            SJson.member(sb, "lens", orEmpty(DeviceInfo.getLens(MainActivity.this, getRootDir())));
            return SJson.endObj(sb);
        }

        public String onInfo() {
            File root = getRootDir();
            StringBuilder sb = SJson.startObj();
            SJson.member(sb, "rsp", "INFO");
            SJson.member(sb, "ok", true);
            SJson.member(sb, "model", orEmpty(DeviceInfo.getModel(MainActivity.this, root)));
            SJson.member(sb, "serial", orEmpty(DeviceInfo.getSerial(MainActivity.this, root)));
            SJson.member(sb, "firmware", orEmpty(DeviceInfo.getFirmwareVersion()));
            SJson.member(sb, "lens", orEmpty(DeviceInfo.getLens(MainActivity.this, root)));
            SJson.member(sb, "batteryPct", DeviceInfo.getBatteryPct(MainActivity.this));
            SJson.member(sb, "batteryRemainMin", DeviceInfo.getBatteryRemainMin(MainActivity.this));
            SJson.member(sb, "mode", MODE_HOTSPOT.equals(savedMode) ? "hotspot" : "wifi");
            SJson.member(sb, "ssid", orEmpty(ssid));
            SJson.member(sb, "password", orEmpty(password));
            SJson.member(sb, "ip", orEmpty(currentIp));
            SJson.member(sb, "ftpPort", FTP_PORT);
            SJson.member(sb, "connectPort", CONNECT_PORT);
            SJson.member(sb, "infoSource", DeviceInfo.infoSource(MainActivity.this));
            sb.append(",\"thumb\":{\"state\":").append(SJson.str(thumbState()))
                    .append(",\"done\":").append(thumbPrefetcher != null ? thumbPrefetcher.getDone() : 0)
                    .append(",\"total\":").append(thumbPrefetcher != null ? thumbPrefetcher.getTotal() : 0)
                    .append('}');
            return SJson.endObj(sb);
        }

        public int onThumbBegin(List<String> paths) {
            if (thumbPrefetcher == null) return 0;
            thumbPrefetcher.begin(paths);
            return thumbPrefetcher.getTotal();
        }

        public void onThumbPause() {
            if (thumbPrefetcher != null) thumbPrefetcher.pause();
        }

        public void onThumbResume() {
            if (thumbPrefetcher != null) thumbPrefetcher.resume();
        }

        public void onThumbCancel() {
            if (thumbPrefetcher != null) thumbPrefetcher.cancel();
        }

        public void onExitApp() {
            handler.postDelayed(new Runnable() {
                public void run() {
                    beginOrderlyExit("传输完成，正在自动退出···");
                }
            }, 300);
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private String thumbState() {
        return thumbPrefetcher != null ? thumbPrefetcher.getState() : ThumbPrefetcher.STATE_IDLE;
    }

    private void beginOrderlyExit() {
        beginOrderlyExit("正在退出···");
    }

    private void beginOrderlyExit(String exitingMsg) {
        if (shuttingDown) return;
        shuttingDown = true;
        isDisablingForFinish = true;
        stationWaiting = false;
        phase = PH_CLOSING;
        removeRadioCallbacks();
        try {
            unregisterReceiver(receiver);
        } catch (Throwable t) {
        }

        exitMsgView.setText(exitingMsg);
        showScreen(DLG_EXITING);
        new Thread(new Runnable() {
            public void run() {
                shutdownServicesAndRadio(savedMode);

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

                        daFinishOnly();
                    }
                });
            }
        }, "ConnectShutdown").start();
    }

    void shutdownServicesAndRadio(String mode) {
        stopFtpServer();
        stopConnectServer();

        if (MODE_HOTSPOT.equals(mode)) {

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

        deleteTraceFiles();
        restoreAutoPowerOff();
    }

    static void deleteTraceFiles(Context c) {
        File[] candidates = new File[]{
                new File("/sdcard/SONYCONNECT_LOG.TXT"),
                new File("/android/storage/sdcard0/SONYCONNECT_LOG.TXT"),
                new File("/android/mnt/sdcard/SONYCONNECT_LOG.TXT"),
        };
        for (int i = 0; i < candidates.length; i++) {
            try {
                if (candidates[i].isFile()) candidates[i].delete();
            } catch (Throwable t) {
            }
        }
        try {
            File priv = new File(c.getFilesDir(), "sonyconnect_log.txt");
            if (priv.isFile()) priv.delete();
        } catch (Throwable t) {
        }
    }

    private void deleteTraceFiles() {
        deleteTraceFiles(getApplicationContext());
        try {
            File root = getRootDir();
            File f = new File(root, "SONYCONNECT_LOG.TXT");
            if (f.isFile()) f.delete();
        } catch (Throwable t) {
        }
    }

    private boolean radiosConfirmedDown() {
        boolean wifiDown;
        try {
            wifiDown = wifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED;
        } catch (Throwable t) {
            wifiDown = false;
        }

        return wifiDown && radio.getLiveGroup() == null;
    }

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

        stopFtpServer();
        stopConnectServer();
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

        try {
            DAConnectionManager mgr = new DAConnectionManager(getApplicationContext());
            mgr.finish();
        } catch (Throwable t) {
        }
    }

    static void killServicesQuietly() {
        MainActivity m = sInstance;
        if (m != null) {
            m.stopFtpServer();
            m.stopConnectServer();
        }
    }

    static void killRadioQuietly(Context context) {
        try {
            com.bi2qfa.sonyconnect.radio.RadioWrapperFactory.getInstance(context)
                    .issueDirectOff();
        } catch (Throwable t) {
        }
    }

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

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            try {
                updateBattery(intent);
            } catch (Throwable t) {
            }
        }
    };

    private void updateBattery(Intent intent) {
        if (intent == null) return;
        try {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                batteryPct = level * 100 / scale;
            }
            batteryIcon.setLevel(batteryPct);
            batteryText.setText(batteryPct >= 0 ? batteryPct + "%" : "--");
        } catch (Throwable t) {
        }
    }

    private void updateMainStatus() {
        StringBuilder sb = new StringBuilder();
        switch (phase) {
            case PH_RUNNING:
                sb.append("状态：运行中\n");
                sb.append("连接方式：").append(MODE_HOTSPOT.equals(savedMode) ? "相机热点" : "Wi-Fi 客户端").append('\n');
                if (MODE_HOTSPOT.equals(savedMode)) {
                    sb.append("热点名称：").append(ssid != null ? ssid : "—").append('\n');
                    sb.append("热点密码：").append(password != null ? password : "—");
                } else {

                    String live = stationSsid();
                    if (live == null || live.length() == 0) live = ssid;
                    sb.append("Wi-Fi：").append(live != null ? live : "—");
                }
                break;
            case PH_FAILED:
                sb.append("状态：启动失败\n");
                sb.append(errorMsg != null ? errorMsg : "").append('\n');
                sb.append("[确定键] 重试");
                break;
            case PH_CLOSING:
                sb.append(modeSwitching ? "状态：正在切换…" : "状态：正在关闭…");
                break;
            case PH_CLOSED:
                sb.append("状态：已关闭");
                break;
            default:
                sb.append("状态：启动中…\n");
                if (MODE_WIFI.equals(savedMode)) {
                    sb.append("正在连接 Wi-Fi 网络\n修改Wi-Fi设置：MENU → 连接设置 → Wi-Fi 设置");
                }
                break;
        }
        statusView.setText(sb.toString());
        updateQrCode();
    }

    private void updateQrCode() {

        if (phase == PH_RUNNING && MODE_HOTSPOT.equals(savedMode)
                && ssid != null && password != null) {
            String wifi = "WIFI:T:WPA;S:" + ssid + ";P:" + password + ";;";
            qrView.setImageBitmap(renderQr(QrCode.encode(wifi), 6, 8, 4));
            setQrVisible(true);
        } else {
            qrView.setImageBitmap(null);
            setQrVisible(false);
        }
    }

    private void setQrVisible(boolean visible) {

        int v = visible ? View.VISIBLE : View.INVISIBLE;
        qrView.setVisibility(v);
        qrCaption.setVisibility(v);
    }

    private void loadAboutAsync() {
        aboutText.setText("设备信息读取中…");
        final File root = getRootDir();
        new Thread("AboutLoader") {
            public void run() {
                final String model = DeviceInfo.getModel(MainActivity.this, root);
                final String serial = DeviceInfo.getSerial(MainActivity.this, root);
                final String fw = DeviceInfo.getFirmwareVersion();
                final String lens = DeviceInfo.getLens(MainActivity.this, root);
                final int remain = DeviceInfo.getBatteryRemainMin(MainActivity.this);
                handler.post(new Runnable() {
                    public void run() {
                        StringBuilder sb = new StringBuilder();
                        sb.append("SonyConnect 1.0\n");
                        sb.append("配套手机端 SonyConnect 1.0 使用\n\n");
                        sb.append("相机型号：").append(model != null ? model : "—").append('\n');
                        sb.append("序列号：").append(serial != null ? serial : "—").append('\n');
                        if (fw != null) sb.append("固件：").append(fw).append('\n');
                        sb.append("镜头：").append(lens != null ? lens : "—").append('\n');
                        sb.append("电量：").append(batteryPct >= 0 ? batteryPct + "%" : "—");
                        if (remain > 0) sb.append("（约 ").append(remain).append(" 分钟）");
                        sb.append('\n');

                        String devIp = MODE_HOTSPOT.equals(savedMode) ? HOTSPOT_IP : currentIp;
                        sb.append("设备IP：").append(devIp != null ? devIp : "—").append('\n');
                        sb.append("FTP 端口：").append(FTP_PORT)
                                .append("    协议端口：").append(CONNECT_PORT).append('\n');
                        sb.append('\n');
                        sb.append("开发者：BI2QFA\n");
                        aboutText.setText(sb);
                    }
                });
            }
        }.start();
    }

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
        private final String[][] items;

        RowAdapter(String[][] items) {
            this.items = items;
        }

        protected abstract int focusPos();

        protected abstract void setFocusPos(int pos);

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
                v = getLayoutInflater().inflate(R.layout.row_two_col, parent, false);
            }
            TextView title = (TextView) v.findViewById(R.id.element_text);
            TextView value = (TextView) v.findViewById(R.id.value_text);
            title.setText(items[position][0]);
            String val = items[position][1];
            value.setVisibility(val.length() == 0 ? View.INVISIBLE : View.VISIBLE);
            value.setText(val);

            boolean isFocused = position == focusPos();
            if (isFocused) {
                v.setBackgroundDrawable(getResources().getDrawable(R.drawable.row_bg_camera));
            } else {
                v.setBackgroundDrawable(null);
            }
            return v;
        }
    }
}
