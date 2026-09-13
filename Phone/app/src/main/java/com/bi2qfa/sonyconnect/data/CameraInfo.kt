package com.bi2qfa.sonyconnect.data

import org.json.JSONObject



























data class CameraInfo(
    
    val guid: String,
    
    val name: String,
    val model: String,
    val serial: String,
    val firmware: String,
    



    val region: String,
    





    val apiVersion: String,
    
    val androidVersion: String,
    
    val androidSdk: Int,
    
    val sdTotalBytes: Long,
    
    val sdUsedBytes: Long,
    
    val lens: String,
    
    val batteryPct: Int,
    
    val batteryRemainMin: Int,
    
    val mode: String,
    
    val ssid: String,
    
    val protoPort: Int,
    
    val filePort: Int,
) {

    



    val sdKnown: Boolean get() = sdTotalBytes > 0 && sdUsedBytes in 0..sdTotalBytes

    
    val sdUsedFraction: Float
        get() = if (sdKnown) (sdUsedBytes.toFloat() / sdTotalBytes).coerceIn(0f, 1f) else 0f

    










    fun applyPing(batteryPct: Int, lens: String): CameraInfo = copy(
        batteryPct = if (batteryPct in 0..100) batteryPct else this.batteryPct,
        lens = lens,
    )

    companion object {

        
        const val UNKNOWN: Int = -1

        
        const val UNKNOWN_SIZE: Long = -1L

        





















        fun fromDeviceInfo(
            json: JSONObject?,
            guid: String,
            fallbackName: String,
            protoPort: Int,
            filePort: Int,
        ): CameraInfo? {
            if (json == null) return null
            val reportedName = json.optString("name", "")
            return CameraInfo(
                guid = guid,
                name = reportedName.ifBlank { fallbackName },
                model = json.optString("model", ""),
                serial = json.optString("serial", ""),
                firmware = json.optString("firmware", ""),
                region = json.optString("region", ""),
                apiVersion = json.optString("apiVersion", ""),
                androidVersion = json.optString("androidVersion", ""),
                androidSdk = json.optInt("androidSdk", UNKNOWN),
                sdTotalBytes = json.optLong("sdTotal", UNKNOWN_SIZE),
                sdUsedBytes = json.optLong("sdUsed", UNKNOWN_SIZE),
                lens = json.optString("lens", ""),
                batteryPct = json.optInt("batteryPct", UNKNOWN),
                batteryRemainMin = json.optInt("batteryRemainMin", UNKNOWN),
                mode = json.optString("mode", ""),
                ssid = json.optString("ssid", ""),
                protoPort = protoPort,
                filePort = filePort,
            )
        }

        



        fun placeholder(
            guid: String,
            name: String,
            protoPort: Int,
            filePort: Int,
        ): CameraInfo = CameraInfo(
            guid = guid,
            name = name,
            model = "",
            serial = "",
            firmware = "",
            region = "",
            apiVersion = "",
            androidVersion = "",
            androidSdk = UNKNOWN,
            sdTotalBytes = UNKNOWN_SIZE,
            sdUsedBytes = UNKNOWN_SIZE,
            lens = "",
            batteryPct = UNKNOWN,
            batteryRemainMin = UNKNOWN,
            mode = "",
            ssid = "",
            protoPort = protoPort,
            filePort = filePort,
        )
    }
}
