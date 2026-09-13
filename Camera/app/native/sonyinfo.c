




















#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "backup.h"
#include "backup_senser.h"

static int read_raw_property(int id, unsigned char *buf, int cap)
{
    int datasize = Backup_get_datasize(id);
    if (datasize <= 0 || datasize > cap)
        return -1;
    int res = Backup_read(id, buf);
    if (res != datasize)
        return -1;
    return datasize;
}


static jstring clean_jstring(JNIEnv *env, const unsigned char *raw, int len)
{
    char clean[80];
    int o = 0;
    for (int i = 0; i < len && o < 78; i++) {
        unsigned char c = raw[i];
        if (c == 0)
            break; 
        if (c < 0x20 || c > 0x7E)
            c = '?';
        clean[o++] = (char) c;
    }
    if (o == 0)
        return NULL;
    clean[o] = '\0';
    return (*env)->NewStringUTF(env, clean);
}

JNIEXPORT jstring JNICALL
Java_com_bi2qfa_sonyconnect_NativeInfo_model(JNIEnv *env, jclass clazz)
{
    (void) clazz;
    unsigned char buf[64];
    int len = read_raw_property(0x003e0005, buf, sizeof(buf));
    if (len <= 0)
        return NULL;
    return clean_jstring(env, buf, len);
}

JNIEXPORT jstring JNICALL
Java_com_bi2qfa_sonyconnect_NativeInfo_serial(JNIEnv *env, jclass clazz)
{
    (void) clazz;
    unsigned char buf[16];
    int len = read_raw_property(0x00e70003, buf, sizeof(buf));
    if (len <= 0)
        return NULL;
    char hex[33];
    int o = 0;
    for (int i = 0; i < len; i++)
        o += sprintf(hex + o, "%02x", buf[i]);
    hex[o] = '\0';
    return (*env)->NewStringUTF(env, hex);
}

















JNIEXPORT jstring JNICALL
Java_com_bi2qfa_sonyconnect_NativeInfo_region(JNIEnv *env, jclass clazz)
{
    (void) clazz;

    
    unsigned char *buf = (unsigned char *) malloc(BACKUP_SENSER_PRESET_DATA_MAX_SIZE);
    if (!buf)
        return NULL;

    jstring out = NULL;
    size_t len = BACKUP_SENSER_PRESET_DATA_MAX_SIZE;
    if (backup_senser_cmd_preset_data_read(1, buf, &len) == 0
            && len >= 0x100
            && len >= BACKUP_PRESET_DATA_OFFSET_REGION + 0x20) {
        
        unsigned char *v = buf + BACKUP_PRESET_DATA_OFFSET_VERSION;
        if (v[0] == 'B' && v[1] == 'K' && (v[2] == '2' || v[2] == '4'))
            out = clean_jstring(env, buf + BACKUP_PRESET_DATA_OFFSET_REGION, 0x20);
    }

    free(buf);
    return out;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_6;
}
