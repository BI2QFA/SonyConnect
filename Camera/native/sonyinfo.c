/*
 * libsonyinfo.so — 相机固定信息直读（OpenMemories-Tweak 已验证方法）。
 *
 * 数据在 Backup 存储里：
 *   model_name     = ID 0x003e0005（≤16 字节 NUL 结尾字符串）
 *   serial_number  = ID 0x00e70003（4 字节，渲染为小写十六进制）
 *   地区           = preset data 偏移 0xC0（≤0x20 字节串，如 CX79101_CN2）
 * 读取走 OSAL 同步消息（drivers/backup.c、drivers/backup_senser.c 原样移植自
 * OpenMemories-Platform）。
 * 链接期的 libosal_uipc.so 是空 stub，运行时由相机 /system/lib 真库解析。
 *
 * ★ 地区这一条与「型号/序列号」走**不同渠道**：型号/序列号是 BACKUP(0x3E014D)
 *   的 get_datasize/read 两条按 ID 取属性；地区是 BACKUP_SENSER(0x3E0166) 的
 *   preset_data_read —— 也就是 OpenMemories-Tweak 里「Backup region」那一行。
 *
 * ★ 只在**进程内**调用（单进程铁律；历届子进程隔离都被相机杀进程组）。
 *   真机曾出现原生层异常带崩主进程，所以：
 *     ① 返回前逐字节清洗为可打印 ASCII —— 杜绝 NewStringUTF 对非法 Modified
 *        UTF-8 的 JNI abort（Backup 数据里的填充字节可能含高位）；
 *     ② Java 侧整条读取包 try/catch(Throwable) 并缓存结果，每个进程只调一次。
 */
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

/* 清洗为可打印 ASCII；空结果返回 NULL */
static jstring clean_jstring(JNIEnv *env, const unsigned char *raw, int len)
{
    char clean[80];
    int o = 0;
    for (int i = 0; i < len && o < 78; i++) {
        unsigned char c = raw[i];
        if (c == 0)
            break; /* NUL 终止 */
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
Java_io_github_bi2qfa_sonyconnect_NativeInfo_model(JNIEnv *env, jclass clazz)
{
    (void) clazz;
    unsigned char buf[64];
    int len = read_raw_property(0x003e0005, buf, sizeof(buf));
    if (len <= 0)
        return NULL;
    return clean_jstring(env, buf, len);
}

JNIEXPORT jstring JNICALL
Java_io_github_bi2qfa_sonyconnect_NativeInfo_serial(JNIEnv *env, jclass clazz)
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

/*
 * 地区（backup region）。
 *
 * 与 OpenMemories-Tweak 的 InfoActivity「Backup region」**逐字同一条路**：
 *   Backup_read_data()    → backup_senser_cmd_preset_data_read(1, buf, 0x100000)
 *   Backup_check_header() → buf[0x0C] 处必须是 "BK2" / "BK4"
 *   Backup_get_region()   → string(&buf[0xC0])
 * 见 OpenMemories-Platform api/backup.cpp:145-178 与 drivers/backup_senser.h
 * 里那句字段注释 `char region[0x20]; // data[0xC0:0xE0]`。
 *
 * 本机固件镜像里该字段是 CX79101_CN2 —— 已逐字节核对：
 *   nflasha2/Backup.bin（881121 字节）偏移 0x0C = "BK4"、偏移 0xC0 = "CX79101_CN2"，
 *   同一分区还躺着 updater/dat4（= 平台里 /setting/updater/dat4），
 *   即该分区在相机上挂作 /setting。
 * （真机上这条读路尚未跑过：相机不在线，见 devlog 的「未验证」一节。）
 */
JNIEXPORT jstring JNICALL
Java_io_github_bi2qfa_sonyconnect_NativeInfo_region(JNIEnv *env, jclass clazz)
{
    (void) clazz;

    /* 1MB 必须在**堆**上：默认线程栈也就 1MB，放栈上直接爆 */
    unsigned char *buf = (unsigned char *) malloc(BACKUP_SENSER_PRESET_DATA_MAX_SIZE);
    if (!buf)
        return NULL;

    jstring out = NULL;
    size_t len = BACKUP_SENSER_PRESET_DATA_MAX_SIZE;
    if (backup_senser_cmd_preset_data_read(1, buf, &len) == 0
            && len >= 0x100
            && len >= BACKUP_PRESET_DATA_OFFSET_REGION + 0x20) {
        /* Backup_check_header 同款（原版是 strcmp 到 NUL，这里按下标比，不越界） */
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
