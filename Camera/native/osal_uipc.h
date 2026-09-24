#pragma once

/* 相机系统库 libosal_uipc.so 的三个入口（OpenMemories-Platform drivers/osal_uipc.h） */
int osal_free_msg(int type, void *addr);
int osal_snd_sync_msg(int type, void *addr);
int osal_valloc_msg_wait(int type, void **addr, int len, int flag);
