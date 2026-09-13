LOCAL_PATH := $(call my-dir)



include $(CLEAR_VARS)
LOCAL_MODULE := osal_uipc
LOCAL_SRC_FILES := osal_uipc.S
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := sonyinfo




LOCAL_SRC_FILES := sonyinfo.c backup.c backup_senser.c mem.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)

LOCAL_CONLYFLAGS += -std=c11
LOCAL_SHARED_LIBRARIES := osal_uipc
include $(BUILD_SHARED_LIBRARY)
