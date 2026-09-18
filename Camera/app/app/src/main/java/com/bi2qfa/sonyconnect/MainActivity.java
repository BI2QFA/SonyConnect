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






















public class MainActivity extends Activity {

    
    private static final int SCR_MAIN = 0;
    private static final int SCR_MENU = 1;
    private static final int SCR_MODE = 2;
    private static final int SCR_ABOUT = 3;
    private static final int DLG_CONFIRM = 4;   
    private static final int DLG_DEFAULT = 5;
    private static final int DLG_EXITING = 6;  
    private static final int SCR_PAIRING = 7;  
    private static final int SCR_PAIRED = 8;   
    private static final int SCR_LOG = 9;      
    private int screen = SCR_MAIN;

    
    private static final int CONFIRM_EXIT = 0;
    private static final int CONFIRM_UNPAIR = 1;
    private static final int CONFIRM_PAIRING = 2;   
    private int pendingConfirm = CONFIRM_EXIT;
    private String pendingUnpairId = null;
    






    private int confirmBack = SCR_MENU;

    
    private static final int PH_STARTING = 0;
    private static final int PH_RUNNING = 1;
    private static final int PH_FAILED = 2;
    private static final int PH_CLOSING = 3;
    private int phase = PH_STARTING;

    
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

    
    
    private int pairedFocus = 0;
    
    private int pairedShownCount = -1;
    private String[][] pairedItems = new String[][]{{"无已配对设备", ""}};
    
    private final Runnable pairingTicker = new Runnable() {
        public void run() {
            
            if (screen != SCR_PAIRING) {
                return;
            }
            
            
            
            if (!shuttingDown) {
                updatePairingScreen();
            }
            handler.postDelayed(this, 1000);
        }
    };

    
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

    
    
    
    private String lastMainStatus = null;
    










    private String lastPhoneCaption = null;
    private final Runnable mainTicker = new Runnable() {
        public void run() {
            if (shuttingDown) {
                return;
            }
            if (screen == SCR_MAIN) {
                String now = buildMainStatusText();
                boolean textChanged = !now.equals(lastMainStatus);
                
                String caption = phoneModelText();
                boolean linkChanged = lastPhoneCaption == null
                        || !lastPhoneCaption.equals(caption);
                if (textChanged) {
                    if (lastMainStatus != null) {
                        AppLog.i("UI", "主屏状态变化 → " + now.replace((char) 10, ' '));
                    }
                    lastMainStatus = now;
                    statusView.setText(now);
                }
                if (linkChanged) {
                    AppLog.i("Net", "手机端一行变化 → " + caption);
                    lastPhoneCaption = caption;
                    
                    
                    refreshMainScreen();
                } else if (textChanged) {
                    updateQrCode();
                }
            }
            
            
            refreshWifiIcon();
            
            
            
            refreshCameraIcon();
            handler.postDelayed(this, 1000);
        }
    };

    


    private class PairingUiEvents implements PtpCameraHandler.PairingEvents {

        public void onPaired(final String peerDeviceName) {
            AppLog.i("Pair", "配对成功：手机=" + peerDeviceName);
            handler.post(new Runnable() {
                public void run() {
                    
                    
                    if (screen == SCR_PAIRING) {
                        leavePairingMode();
                        aboutFromPairing = false;
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
                    
                    
                    
                    showNotice("配对码已连续错误 " + max + " 次\n已自动更换新的配对码", "确定",
                            NOTICE_GOTO_PAIRING, true);
                }
            });
        }
    }

    
    private View screenMain, screenMenu, screenMode, screenAbout, screenPairing, screenPaired,
            screenLog, dlgConfirm, dlgDefault, dlgExiting;
    private TextView statusView, confirmMsg, qrCaption, exitMsgView;
    
    private TextView aboutTitle, aboutMeta, aboutCode;
    private ImageView qrView;
    
    private TextView logText;
    private ScrollView logScroll;
    private TextView btnConfirmOk, btnConfirmCancel, btnDefaultOk, btnDefaultNo;
    private TextView pairingCodeText, pairingCountdownText, pairingHintText;
    private ListView menuList, modeList, pairedList;
    
    




    private static final int[] HEADER_IDS = {
            R.id.header_main, R.id.header_menu, R.id.header_paired,
            R.id.header_pairing, R.id.header_about, R.id.header_log,
    };
    
    private ImageView dlgConfirmWarn;

    
    







    private View mainBlockDiagram, mainBlockQr, mainBlockStatus;
    
    private ImageView mainIconPhone, mainIconDots;
    
    private ImageView mainIconLeft;
    
    private int mainPhoneIconRes = 0;
    
    private int mainCameraIconRes = 0;
    private TextView mainCapLeft, mainCapRight, btnMainLeft, btnMainRight;
    
    private int mainFocus = 0;
    
    private int mainBlock = 0;

    
    private int batteryPct = -1;
    
    private int lastLoggedBattery = -1;

    
    
    
    
    
    
    private final String[][] menuItems = {
            {"连接设置", "未设置"},
            {"进入配对模式", ""},
            {"已配对设备", "无"},
            {"关于", ""},
            {"退出应用程序", ""},
    };

    







    private final String[][] modeItems = {
            {"连接到 Wi-Fi 网络", ""},
            {"使用相机热点", ""},
    };

    private String savedMode = null;

    
    private WifiManager wifiManager;
    private RadioWrapper radio;

    private PtpIpServer ptpServer;
    private PtpCameraHandler ptpHandler;
    private PairingStore pairingStore;
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
    










    private boolean menuFromPairing = false;

    












    private int pairingBack = SCR_MAIN;

    
    private static final int ABOUT_ENTER_COUNT = 10;
    
    private static final long ABOUT_ENTER_WINDOW_MS = 2000;
    
    private static final int LOG_SHOW_LINES = 300;
    private int aboutEnterCount;
    private long aboutEnterLast;

    
    private boolean hasPairedDevice() {
        return pairingStore != null && pairingStore.size() > 0;
    }
    
    private boolean modeFromPairing = false;

    







    private int modeSwitchTarget = SCR_MAIN;

    
    
    
    
    
    private boolean aboutFromPairing = false;

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

        AppLog.i("Life", "onCreate：版本 " + DeviceInfo.getFirmwareVersion()
                + " 界面 vc 见 build.gradle，sdk=" + Build.VERSION.SDK_INT);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        savedMode = prefs.getString(KEY_MODE, null);
        AppLog.i("Life", "onCreate：上次连接方式=" + savedMode);

        
        
        
        
        
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

        
        mainBlockDiagram = findViewById(R.id.main_block_diagram);
        mainBlockQr = findViewById(R.id.main_block_qr);
        mainBlockStatus = findViewById(R.id.status_view);
        mainIconLeft = (ImageView) findViewById(R.id.main_icon_left);
        mainIconPhone = (ImageView) findViewById(R.id.main_icon_phone);
        mainIconDots = (ImageView) findViewById(R.id.main_icon_dots);
        mainCapLeft = (TextView) findViewById(R.id.main_cap_left);
        mainCapRight = (TextView) findViewById(R.id.main_cap_right);
        btnMainLeft = (TextView) findViewById(R.id.btn_main_left);
        btnMainRight = (TextView) findViewById(R.id.btn_main_right);

        
        applyIconFont(R.id.footer_key_main, R.id.footer_key_menu,
                R.id.footer_key_menu_back, R.id.footer_key_mode,
                R.id.footer_key_pairing, R.id.footer_key_pairing_menu, R.id.footer_key_about,
                R.id.footer_key_paired, R.id.footer_key_paired_menu,
                R.id.footer_key_paired_back,
                R.id.footer_key_dlg_exit, R.id.footer_key_dlg_default,
                R.id.footer_key_log);

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
                onMainButton(0);
            }
        });
        btnMainRight.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onMainButton(1);
            }
        });

        menuList = (ListView) findViewById(R.id.menu_list);
        modeList = (ListView) findViewById(R.id.mode_list);
        pairedList = (ListView) findViewById(R.id.paired_list);

        
        
        
        
        
        
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
            
            
            
            modeFromMenu = false;
            modeFromPairing = false;
            showScreen(SCR_MODE);
            return;
        }
        if (!startedOnce) {
            startedOnce = true;
            applyMode(); 
            
            
            
            
            
            if (!hasPairedDevice()) {
                requestPairing();
            }
            return;
        }
        showScreen(screen);
    }

    @Override
    protected void onPause() {
        
        
        
        
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
        AppLog.i("Key", "scan=" + event.getScanCode() + "(" + keyName(event.getScanCode())
                + ") keyCode=" + keyCode + " screen=" + screen);
        if (shuttingDown) {
            return true; 
        }
        switch (event.getScanCode()) {
            case ScalarInput.ISV_KEY_MENU:
                onMenuKey();
                return true;
            case ScalarInput.ISV_KEY_DELETE:
                
                
                
                
                
                if (exitDialogByStrayKey()) return true;
                if (screen == SCR_MAIN) {
                    menuFocus = 0;
                    menuFromPairing = false;   
                    showScreen(SCR_MENU);
                } else if (screen == SCR_PAIRING) {
                    menuFocus = 0;
                    menuFromPairing = true;    
                    showScreen(SCR_MENU);
                }
                return true;
            case ScalarInput.ISV_KEY_ENTER:
                onEnterKey();
                return true;
            case ScalarInput.ISV_KEY_UP:
            case ScalarInput.ISV_KEY_DOWN:
                
                
                
                step(event.getScanCode() == ScalarInput.ISV_KEY_UP ? STEP_UP : STEP_DOWN);
                return true;
            case ScalarInput.ISV_KEY_LEFT:
                
                
                
                if (exitDialogByStrayKey()) return true;
                step(STEP_LEFT);
                return true;
            case ScalarInput.ISV_KEY_RIGHT:
                if (exitDialogByStrayKey()) return true;
                step(STEP_RIGHT);
                return true;
            
            
            
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
            
            case ScalarInput.ISV_DIAL_KURU_STATUS:
            case ScalarInput.ISV_DIAL_1_STATUS:
            case ScalarInput.ISV_DIAL_2_STATUS:
            case ScalarInput.ISV_DIAL_3_STATUS:
                return true;
            default:
                
                
                
                
                if (exitDialogByStrayKey()) return true;
                return super.onKeyDown(keyCode, event);
        }
    }

    






    private boolean exitDialogByStrayKey() {
        if (screen != DLG_CONFIRM || noticeMode) {
            return false;
        }
        AppLog.i("UI", "确认弹窗：非上下键 → 直接退出（取消）");
        showScreen(confirmBack);
        return true;
    }

    
    private boolean onDial(boolean clockwise) {
        boolean horizontal = (screen == SCR_MAIN);
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

    private void onMenuKey() {
        switch (screen) {
            case SCR_MAIN:
                
                
                showConfirm(CONFIRM_EXIT);
                break;
            
            case SCR_MENU:
                
                
                
                
                
                
                if (hasPairedDevice()) {
                    showScreen(SCR_MAIN);
                } else {
                    requestPairing();
                }
                break;
            case SCR_LOG:
                
                AppLog.i("Key", "日志屏：MENU 回关于页");
                showScreen(SCR_ABOUT);
                break;
            case SCR_ABOUT:
                
                
                
                if (aboutFromPairing) {
                    aboutFromPairing = false;
                    if (hasPairedDevice()) {
                        showScreen(SCR_PAIRING);
                    } else {
                        requestPairing();
                    }
                } else {
                    showScreen(SCR_MAIN);
                }
                break;
            case SCR_PAIRING:
                
                
                
                
                
                
                AppLog.i("Key", "配对页：MENU 返回 " + screenName(pairingBack));
                showScreen(pairingBack);
                break;
            case SCR_PAIRED:
                
                
                
                menuFromPairing = false;
                showScreen(SCR_MENU);
                break;
            case SCR_MODE:
                
                
                
                
                if (modeFromMenu) {
                    
                    menuFromPairing = modeFromPairing;
                    showScreen(SCR_MENU);
                } else {
                    showConfirm(CONFIRM_EXIT);
                }
                break;
            case DLG_CONFIRM:
                
                
                
                if (noticeMode) {
                    finishNotice();
                } else {
                    showScreen(confirmBack);
                }
                break;
            case DLG_DEFAULT:
                
                showScreen(SCR_MODE);
                break;
            default:
                break;
        }
    }

    
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
                    
                    onMainButton(mainFocus);
                }
                break;
            case SCR_MENU:
                onMenuActivated(menuFocus);
                break;
            case SCR_MODE:
                onModeActivated(modeFocus);
                break;
            case SCR_PAIRING:
                
                
                break;
            case SCR_PAIRED:
                onPairedActivated(pairedFocus);
                break;
            case SCR_ABOUT:
                
                
                
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

    
    
    
    
    
    
    
    

    private static final int STEP_UP = 0;
    private static final int STEP_DOWN = 1;
    private static final int STEP_LEFT = 2;
    private static final int STEP_RIGHT = 3;

    




    private void step(int dir) {
        switch (screen) {
            case SCR_MAIN:
                
                if (dir == STEP_LEFT) btnMainLeft.requestFocus();
                else if (dir == STEP_RIGHT) btnMainRight.requestFocus();
                break;
            case SCR_MENU:
                
                
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
                
                if (dir == STEP_UP) scrollLog(-1);
                else if (dir == STEP_DOWN) scrollLog(1);
                break;
            default:
                
                break;
        }
    }

    
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

    

    private void showScreen(int target) {
        int prevForLog = screen;
        int prev = screen;
        screen = target;
        handler.removeCallbacks(pairedTicker);   
        
        
        
        
        
        AppLog.i("UI", "切屏 " + screenName(prevForLog) + " → " + screenName(target));
        if (prev == SCR_PAIRING && target != SCR_PAIRING) {
            leavePairingMode();
        }
        
        
        
        
        
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
            
            mainFocus = 0;
            refreshMainScreen();
            btnMainLeft.requestFocus();
        } else if (target == SCR_MENU) {
            
            RowAdapter ma = (RowAdapter) menuList.getAdapter();
            ma.setItems(menuItems);
            if (menuFocus >= ma.getCount()) {
                menuFocus = ma.getCount() - 1;
            }
            refreshMenuValues();   
            ma.notifyDataSetChanged();
            menuList.requestFocus();
            menuList.setSelection(ma.focusPos());
            ma.notifyDataSetChanged();
        } else if (target == SCR_MODE) {
            modeList.requestFocus();
            modeList.setSelection(((RowAdapter) modeList.getAdapter()).focusPos());
            ((BaseAdapter) modeList.getAdapter()).notifyDataSetChanged();
        } else if (target == SCR_ABOUT) {
            
            
            updateAboutText();
            aboutEnterCount = 0;      
        } else if (target == SCR_LOG) {
            updateLogScreen();
        } else if (target == SCR_PAIRING) {
            enterPairingMode();
        } else if (target == SCR_PAIRED) {
            rebuildPairedItems();
            pairedList.requestFocus();
            pairedList.setSelection(pairedFocus);
            ((BaseAdapter) pairedList.getAdapter()).notifyDataSetChanged();
            handler.removeCallbacks(pairedTicker);
            handler.postDelayed(pairedTicker, 1000);
        } else if (target == DLG_CONFIRM) {
            
            
            dlgConfirmWarn.setVisibility(
                    !noticeMode || noticeWarn ? View.VISIBLE : View.GONE);
            confirmFocus = 0;
            btnConfirmOk.requestFocus();
        } else if (target == DLG_DEFAULT) {
            defFocus = 0;
            btnDefaultOk.requestFocus();
        }
        
        
        refreshWifiIcon();
    }

    private void onMenuActivated(int pos) {
        
        
        
        
        if (pos == 0) {
            modeFromMenu = true;
            modeFromPairing = menuFromPairing;
            showScreen(SCR_MODE);
        } else if (pos == 1) {
            
            
            requestPairing();
        } else if (pos == 2) {
            showScreen(SCR_PAIRED);
        } else if (pos == 3) {
            aboutFromPairing = menuFromPairing;
            showScreen(SCR_ABOUT);
        } else if (pos == 4) {
            showConfirm(CONFIRM_EXIT);
        }
    }

    
    private void showConfirm(int what) {
        
        
        
        
        
        resetNoticePanel();
        pendingConfirm = what;
        confirmBack = screen;   
        if (what == CONFIRM_EXIT) {
            confirmMsg.setText("确定要退出 SonyConnect 吗？");
        } else if (what == CONFIRM_UNPAIR) {
            
        } else {
            
            
            confirmMsg.setText("进入配对模式？\n此操作会断开与手机端的连接");
        }
        showScreen(DLG_CONFIRM);
    }

    
    
    
    
    

    
    private boolean noticeMode;
    
    private boolean noticeWarn;
    private int noticeAction;
    private static final int NOTICE_NONE = 0;
    
    private static final int NOTICE_GOTO_PAIRING = 2;

    private void showNotice(String msg, String okLabel, int action, boolean warn) {
        noticeMode = true;
        noticeWarn = warn;
        noticeAction = action;
        confirmBack = screen;
        confirmMsg.setText(msg);
        btnConfirmOk.setText(okLabel);
        
        
        
        
        btnConfirmCancel.setVisibility(View.GONE);
        
        
        moveButtonTop(btnConfirmOk, 336);
        showScreen(DLG_CONFIRM);   
    }

    
    private void moveButtonTop(TextView b, int topMarginPx) {
        android.widget.RelativeLayout.LayoutParams lp =
                (android.widget.RelativeLayout.LayoutParams) b.getLayoutParams();
        lp.topMargin = topMarginPx;
        b.setLayoutParams(lp);
    }

    







    private void resetNoticePanel() {
        noticeMode = false;
        noticeAction = NOTICE_NONE;
        btnConfirmOk.setText("确定");
        moveButtonTop(btnConfirmOk, 270);
        btnConfirmCancel.setVisibility(View.VISIBLE);
    }

    
    private void finishNotice() {
        int act = noticeAction;
        resetNoticePanel();
        if (act == NOTICE_GOTO_PAIRING) {
            
            
            notePairingBack(confirmBack);
            
            
            
            if (phase == PH_FAILED) {
                AppLog.w("Radio", "配对提示确认：无线电仍在致命错误态 → 落主界面看错误");
                showScreen(SCR_MAIN);
            } else {
                showScreen(SCR_PAIRING);
            }
        } else {
            showScreen(confirmBack);
        }
    }

    















    private void requestPairing() {
        if (hasPairedDevice()) {
            showConfirm(CONFIRM_PAIRING);
        } else {
            promptPairingRequired();
        }
    }

    







    private void notePairingBack(int from) {
        if (from == SCR_MAIN || from == SCR_MENU || from == SCR_MODE) {
            pairingBack = from;
        }
    }

    private void runConfirmAction(boolean ok) {
        
        if (noticeMode) {
            finishNotice();
            return;
        }
        int what = pendingConfirm;
        if (what == CONFIRM_UNPAIR) {
            String id = pendingUnpairId;
            pendingUnpairId = null;
            if (!ok || id == null) {
                showScreen(SCR_PAIRED);   
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
            
            
            
            
            showScreen(SCR_PAIRED);
            return;
        }
        if (!ok) {
            showScreen(confirmBack);   
            return;
        }
        if (what == CONFIRM_EXIT) {
            
            
            
            beginOrderlyExit();
        } else if (what == CONFIRM_PAIRING) {
            
            
            
            
            
            
            if (ptpServer != null) {
                try {
                    ptpServer.dropAllConnections();
                } catch (Throwable t) {
                }
            }
            
            notePairingBack(confirmBack);
            showScreen(SCR_PAIRING);
        } else {
            showScreen(SCR_MAIN);
        }
    }

    private void onModeActivated(int pos) {
        AppLog.i("UI", "连接设置选中第 " + pos + " 项");
        
        pendingMode = pos == 0 ? MODE_WIFI : MODE_HOTSPOT;
        showScreen(DLG_DEFAULT);
    }

    











    private void finishModeChoice(boolean asDefault) {
        
        
        
        
        
        
        
        final boolean firstLaunch = !modeFromMenu && !modeFromPairing && !hasPairedDevice();
        final boolean toPairing = modeFromPairing || firstLaunch;
        modeFromPairing = false;
        
        
        modeSwitchTarget = toPairing ? SCR_PAIRING : SCR_MAIN;
        if (pendingMode != null) {
            final String newMode = pendingMode;
            
            final boolean def = asDefault || savedMode == null;
            pendingMode = null;
            if (newMode.equals(savedMode)) {
                
                if (def) prefs.edit().putString(KEY_MODE, savedMode).commit();
                refreshMenuValues();
                landAfterModeChoice(toPairing);
                return;
            }
            beginModeSwitch(newMode, def);
            
            
            
            
            if (toPairing && hasPairedDevice() && !firstLaunch) {
                showScreen(SCR_PAIRING);
            }
            return;
        }
        landAfterModeChoice(toPairing);
    }

    







    private void landAfterModeChoice(boolean toPairing) {
        
        
        
        
        
        final boolean toPair = toPairing || !hasPairedDevice();
        if (!toPair) {
            showScreen(SCR_MAIN);
        } else if (hasPairedDevice()) {
            showScreen(SCR_PAIRING);
        } else {
            promptPairingRequired();
        }
    }

    






    private void promptPairingRequired() {
        
        
        
        
        
        int back = (screen == DLG_EXITING || screen == DLG_DEFAULT) ? SCR_MODE : screen;
        showNotice("请先与一台手机配对", "进入配对模式", NOTICE_GOTO_PAIRING, false);
        confirmBack = back;
    }

    

    private void refreshMenuValues() {
        String v = MODE_WIFI.equals(savedMode) ? "Wi-Fi"
                : MODE_HOTSPOT.equals(savedMode) ? "热点" : "未设置";
        menuItems[0][1] = v;
        int paired = pairingStore != null ? pairingStore.size() : 0;
        menuItems[2][1] = paired > 0 ? paired + " 台" : "无";
    }

    
    
    
    
    
    

    






    private void enterPairingMode() {
        AppLog.i("Pair", "进入配对模式（开窗 180s）");
        if (pairingStore == null) {
            return;
        }
        if (!pairingStore.isOpen(System.currentTimeMillis())) {
            pairingStore.openWindow(System.currentTimeMillis());
        }
        updatePairingScreen();
        handler.removeCallbacks(pairingTicker);
        handler.postDelayed(pairingTicker, 1000);
    }

    






    private static String shortCode(String hex) {
        if (hex == null || hex.length() == 0) {
            return "—";
        }
        return hex.length() <= 8 ? hex : hex.substring(0, 8);
    }

    









    private void updateAboutText() {
        String id = pairingStore != null ? pairingStore.deviceIdHex() : null;
        aboutTitle.setText("SonyConnect 2.0");
        aboutMeta.setText("配套手机端 SonyConnect 2.0 使用\n开发者：BI2QFA");
        aboutCode.setText("本机设备码：" + shortCode(id));
    }

    






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

    
    private void leavePairingMode() {
        AppLog.i("Pair", "离开配对页（关窗作废当前码）");
        handler.removeCallbacks(pairingTicker);
        if (pairingStore != null) {
            pairingStore.closeWindow();
        }
        refreshMenuValues();
        ((BaseAdapter) menuList.getAdapter()).notifyDataSetChanged();
    }

    
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
                
                
                
                pairedItems[i] = new String[]{name, shortCode(pd.peerDeviceId)};
            }
        }
        ((RowAdapter) pairedList.getAdapter()).setItems(pairedItems);
        pairedShownCount = pairingStore == null ? 0 : pairingStore.size();
        if (pairedFocus >= pairedItems.length) {
            pairedFocus = 0;
        }
    }

    





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
            
            
            
            pairingStore.openWindow(now);
            code = pairingStore.code(now);
            if (code == null) {
                pairingCodeText.setText("- - - - - -");
                pairingCountdownText.setText("");
                pairingHintText.setText("在手机端输入配对码");
                return;
            }
        }
        StringBuilder pretty = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0) pretty.append(' ');
            pretty.append(code.charAt(i));
        }
        pairingCodeText.setText(pretty.toString());
        long left = (pairingStore.remainingMs(now) + 999) / 1000;
        pairingCountdownText.setText("此配对码有效期剩余 " + left + " 秒");
        
        pairingHintText.setText("在手机端输入配对码");
    }

    




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
        confirmMsg.setText("解除与 " + name + " 的配对？");   
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

    



    private void beginModeSwitch(final String newMode, final boolean asDefault) {
        final String oldMode = savedMode;
        AppLog.w("Mode", "切换连接方式 " + oldMode + " → " + newMode + "（设为默认=" + asDefault + "）");
        shuttingDown = true;      
        stationWaiting = false;
        modeSwitching = true;     
        
        
        
        removeRadioCallbacks();
        
        
        
        
        
        
        
        
        
        boolean noticeSent = false;
        if (ptpServer != null) {
            try {
                ptpServer.pushModeSwitching(MODE_HOTSPOT.equals(newMode)
                        ? PtpCodec.MODE_CODE_HOTSPOT
                        : PtpCodec.MODE_CODE_WIFI);
                noticeSent = true;
            } catch (Throwable ignored) {
                
            }
        }
        final boolean noticePushed = noticeSent;
        phase = PH_CLOSING;
        
        
        exitMsgView.setText("正在切换模式···");
        showScreen(DLG_EXITING);
        new Thread("ModeSwitch") {
            public void run() {
                if (noticePushed) {
                    
                    
                    
                    
                    
                    
                    
                    
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException ignored) {
                        
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
                        
                        
                        
                        
                        mainBlock = 0;
                        
                        
                        
                        
                        
                        
                        
                        if (!hasPairedDevice()) {
                            
                            
                            
                            promptPairingRequired();
                        } else if (modeSwitchTarget == SCR_PAIRING) {
                            showScreen(SCR_PAIRING);
                        } else {
                            showScreen(SCR_MAIN);
                        }
                        applyMode();
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
        
        
        
        
        if (screen == SCR_PAIRING) {
            leavePairingMode();
            showScreen(SCR_MAIN);
        }
        
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
        if (thumbPrefetcher == null) {
            thumbPrefetcher = new ThumbPrefetcher(getRootDir());
        }
        
        
        
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
                RecSession.get().configure(getRootDir(), new RecSession.Listener() {
                    public void onRecEvent(int kind, int a, int b) {
                        PtpIpServer srv = ptpServer;
                        if (srv != null) {
                            srv.pushEvent(PtpCodec.EV_REC, 0, new int[]{kind, a, b});
                        }
                    }
                });
            } catch (IOException e) {
                ptpServer = null;
                ptpHandler = null;
                return false;
            }
        }
        
        
        AppLog.i("Ptp", "服务已就绪：proto端口=" + ptpServer.getProtoPort()
                + " file端口=" + ptpServer.getFilePort());
        handler.removeCallbacks(mainTicker);
        handler.postDelayed(mainTicker, 1000);
        return true;
    }

    




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

    

    private void beginOrderlyExit() {
        beginOrderlyExit("正在退出···");
    }

    
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
        AppLog.i("Exit", "收尾：停服务 + 无线电归位（方式=" + mode + "）");
        stopPtpServer();

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

    









    private static final String[] TRACE_NAMES = {
            "SONYCONNECT_LOG.TXT",      
            "SonyConnect-trace.log",    
            "SonyConnect-crash.log",    
    };

    
    static void deleteTraceFiles(Context c) {
        deleteTraceFiles(c, null);
    }

    


    static void deleteTraceFiles(Context c, File extraRoot) {
        
        
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
            
            for (int j = 0; j < TRACE_NAMES.length; j++) {
                deleteQuietly(new File(root, TRACE_NAMES[j]));
            }
            
            
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

    
    private int checkBatteryLevel(int value) {
        int[] t = batteryThresholds();
        if (value < 1) return 0;
        if (value <= t[2]) return 1;
        if (value <= t[1]) return 2;
        if (value <= t[0]) return 3;
        return 4;
    }

    
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

    

    private void updateMainStatus() {
        lastMainStatus = buildMainStatusText();
        statusView.setText(lastMainStatus);
        refreshMainScreen();
        refreshWifiIcon();
        refreshCameraIcon();
    }

    









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

    

    














    private void refreshMainScreen() {
        if (btnMainLeft == null) {
            return;
        }
        String leftLabel = MODE_HOTSPOT.equals(savedMode) ? "显示二维码"
                : MODE_WIFI.equals(savedMode) ? "Wi-Fi 设置" : "连接方式";
        setTextIfChanged(btnMainLeft, leftLabel);

        String capLeft = "连接方式";
        if (MODE_HOTSPOT.equals(savedMode)) {
            capLeft = "相机热点";
        } else if (MODE_WIFI.equals(savedMode)) {
            String live = stationSsid();
            capLeft = (live != null && live.length() > 0) ? "Wi-Fi已连接" : "Wi-Fi未连接";
        }
        setTextIfChanged(mainCapLeft, capLeft);
        setTextIfChanged(mainCapRight, phoneModelText());

        
        
        
        
        
        boolean linked = ptpServer != null && ptpServer.connectedClientCount() > 0;
        int icon = linked ? R.drawable.ic_sync_phone_on : R.drawable.ic_sync_phone_off;
        if (icon != mainPhoneIconRes) {   
            mainPhoneIconRes = icon;
            mainIconPhone.setImageResource(icon);
            
            
            
            mainIconDots.setImageResource(
                    linked ? R.drawable.ic_sync_link_on : R.drawable.ic_sync_link_off);
        }

        mainBlockDiagram.setVisibility(mainBlock == 0 ? View.VISIBLE : View.GONE);
        mainBlockQr.setVisibility(mainBlock == 1 ? View.VISIBLE : View.GONE);
        mainBlockStatus.setVisibility(mainBlock == 2 ? View.VISIBLE : View.GONE);
        updateQrCode();
    }

    
    private String phoneModelText() {
        if (ptpServer == null || ptpServer.connectedClientCount() <= 0) {
            return "未连接";
        }
        String name = ptpServer.connectedClientName();
        return (name != null && name.length() > 0) ? name : "已连接";
    }

    





    private void onMainButton(int which) {
        AppLog.i("UI", "主界面按钮 " + (which == 0 ? "左" : "右") + "（phase=" + phase + "）");
        if (phase == PH_FAILED) {
            retryFromFailure();
            return;
        }
        if (which == 1) {
            mainBlock = (mainBlock == 2) ? 0 : 2;
            refreshMainScreen();
            return;
        }
        if (MODE_HOTSPOT.equals(savedMode)) {
            mainBlock = (mainBlock == 1) ? 0 : 1;
            refreshMainScreen();
        } else if (MODE_WIFI.equals(savedMode)) {
            jumpToWifiSettings();
        } else {
            
            modeFromMenu = true;
            modeFromPairing = false;
            showScreen(SCR_MODE);
        }
    }

    private void setTextIfChanged(TextView v, String s) {
        if (v != null && s != null && !s.equals(v.getText().toString())) {
            v.setText(s);
        }
    }

    
    private void applyIconFont(int... ids) {
        android.graphics.Typeface tf;
        try {
            tf = android.graphics.Typeface.createFromAsset(getAssets(), "fonts/icons.ttf");
        } catch (Throwable t) {
            return;   
        }
        for (int i = 0; i < ids.length; i++) {
            TextView v = (TextView) findViewById(ids[i]);
            if (v != null) {
                v.setTypeface(tf);
            }
        }
    }

    


    private String buildMainStatusText() {
        StringBuilder sb = new StringBuilder();
        switch (phase) {
            case PH_RUNNING:
                sb.append("状态：运行中\n");
                if (RecSession.get().isActive()) {
                    sb.append("遥控拍摄中\n");
                }
                sb.append("手机端：").append(phoneStatusText()).append('\n');
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
            default:
                sb.append("状态：启动中…\n");
                if (MODE_WIFI.equals(savedMode)) {
                    
                    
                    sb.append("正在连接 Wi-Fi 网络");
                }
                break;
        }
        return sb.toString();
    }

    





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

    







    private void notifyPeerUnpaired(String id) {
        if (ptpServer == null || id == null) {
            return;
        }
        String connected = ptpServer.connectedClientIdHex();
        if (connected != null && connected.equalsIgnoreCase(id)) {
            ptpServer.pushPairRemoved();
        }
    }

    private void updateQrCode() {
        
        if (phase == PH_RUNNING && MODE_HOTSPOT.equals(savedMode)
                && ssid != null && password != null) {
            String wifi = "WIFI:T:WPA;S:" + ssid + ";P:" + password + ";;";
            qrView.setImageBitmap(renderQr(QrCode.encode(wifi), 6, 8, 4));
            qrView.setVisibility(View.VISIBLE);
            qrCaption.setVisibility(View.VISIBLE);
        } else {
            qrView.setImageBitmap(null);
            
            
            qrView.setVisibility(View.INVISIBLE);
            qrCaption.setVisibility(View.INVISIBLE);
        }
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
        private String[][] items;
        
        private int rowLayout;
        

        private int focusDrawable;

        RowAdapter(String[][] items, int rowLayout, int focusDrawable) {
            this.items = items;
            this.rowLayout = rowLayout;
            this.focusDrawable = focusDrawable;
        }

        
        void setItems(String[][] next) {
            if (next != null) {
                items = next;
                notifyDataSetChanged();
            }
        }

        protected abstract int focusPos();

        protected abstract void setFocusPos(int pos);

        
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
            
            ImageView radio = (ImageView) v.findViewById(R.id.row_radio);
            if (radio != null) {
                radio.setImageResource(radioOn(position)
                        ? R.drawable.radio_on : R.drawable.radio_off);
            }
            
            
            
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
