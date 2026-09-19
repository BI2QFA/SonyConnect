LOCAL_PATH := $(call my-dir)

# stub 共享库：只为让 libsonyinfo.so 的 DT_NEEDED(libosal_uipc.so) 链接通过；
# 不打进 APK，运行时由相机系统库解析。
include $(CLEAR_VARS)
LOCAL_MODULE := osal_uipc
LOCAL_SRC_FILES := osal_uipc.S
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := sonyinfo
# 三个 .c 都是 OpenMemories-Platform 的同名移植：
#   backup.c         BACKUP(0x3E014D) 按 ID 读属性（型号/序列号）
#   backup_senser.c  BACKUP_SENSER(0x3E0166) 读 preset data（地区 = Tweak 那条路）
#   mem.c            /dev/mem mmap（preset data 881121 字节，对面回指针时要用）
LOCAL_SRC_FILES := sonyinfo.c backup.c backup_senser.c mem.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)
# 消息结构里用了匿名 struct/union（C11）—— 与平台同款写法，故钉住标准
LOCAL_CONLYFLAGS += -std=c11
LOCAL_SHARED_LIBRARIES := osal_uipc
include $(BUILD_SHARED_LIBRARY)
