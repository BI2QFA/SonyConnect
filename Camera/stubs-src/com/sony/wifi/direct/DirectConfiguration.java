package com.sony.wifi.direct;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Sony 私有 API 编译桩（compileOnly，不进 APK）。
 */
public class DirectConfiguration implements Parcelable {

    public static final Parcelable.Creator<DirectConfiguration> CREATOR =
            new Parcelable.Creator<DirectConfiguration>() {
        public DirectConfiguration createFromParcel(Parcel source) {
            return new DirectConfiguration();
        }
        public DirectConfiguration[] newArray(int size) {
            return new DirectConfiguration[size];
        }
    };

    public int describeContents() { return 0; }
    public void writeToParcel(Parcel dest, int flags) {}

    public String getSsid() { return null; }
    public String getPreSharedKey() { return null; }
    public int getNetworkId() { return -1; }
}
