package com.bi2qfa.sonyconnect.radio;

import android.os.Parcel;
import android.os.Parcelable;







public interface RadioWrapper {

    
    String ACTION_DIRECT_STATE_CHANGED = "bi2qfa.sony.ftp.radio.DIRECT_STATE_CHANGED";
    String ACTION_GROUP_CREATE_SUCCESS = "bi2qfa.sony.ftp.radio.GROUP_CREATE_SUCCESS";
    String ACTION_GROUP_CREATE_FAILURE = "bi2qfa.sony.ftp.radio.GROUP_CREATE_FAILURE";
    String ACTION_STA_CONNECTED        = "bi2qfa.sony.ftp.radio.STA_CONNECTED";
    String ACTION_STA_DISCONNECTED     = "bi2qfa.sony.ftp.radio.STA_DISCONNECTED";

    
    int DIRECT_STATE_DISABLED = 1;
    int DIRECT_STATE_ENABLED  = 3;
    int DIRECT_STATE_UNKNOWN  = 5;

    String EXTRA_PREV_STATE = "prev_state";
    String EXTRA_STATE      = "state";
    String EXTRA_CONFIG     = "config";
    String EXTRA_STA_ADDR   = "sta_addr";

    
    int NET_ID_PERSISTENT_GO = -2;

    

    
    boolean setDirectEnabled(boolean enable);

    



    void issueDirectOff();

    




    void removeGroup();

    
    boolean isDirectEnabled();

    
    GroupConfig getLiveGroup();

    



    boolean startGo(int networkId);

    
    void configureIdentity(String ssidPostfix, String modelName, String deviceName);

    
    String getLastError();

    
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
