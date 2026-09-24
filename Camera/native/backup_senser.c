/*
 * Backup preset data 读取（原样移植自 OpenMemories-Platform drivers/backup_senser.c，
 * 只保留只读所需的 preset_data_read 一条消息；写分支与 MODE_ANDROID 下被禁用的
 * preset_data_status / version 两条都没搬）。
 *
 * ★ 消息布局必须与平台逐字段一致（type/err/data_len/res_ptr/res_len + 0x130 联合体），
 *   这是与 BIONZ 侧约定的 ABI，改一个字段大小就废了。
 */
#include <stdarg.h>
#include <string.h>

#include "backup_senser.h"
#include "mem.h"
#include "osal_uipc.h"

#define OSAL_MSG_BACKUP_SENSER 0x3E0166

#define MODE_READ 0

#define TYPE_ERROR 0
#define TYPE_START 1
#define TYPE_DONE 8
#define TYPE_FREE 16
#define TYPE_FREE_DONE 32

struct backup_senser_msg {
    char type;
    char err;
    int data_len;
    int res_ptr;
    size_t res_len;
    union {
        struct {
            int write_ptr;
            size_t write_len;
            char data[0x128];
        };
        struct {
            char read_data[0x130];
        };
    };
};

static int backup_senser_send_msg(struct backup_senser_msg *data)
{
    int res;
    void *msg;

    res = osal_valloc_msg_wait(OSAL_MSG_BACKUP_SENSER, &msg,
                               sizeof(struct backup_senser_msg), 1);
    if (res)
        return res;

    memcpy(msg, data, sizeof(struct backup_senser_msg));

    res = osal_snd_sync_msg(OSAL_MSG_BACKUP_SENSER, msg);
    if (res) {
        /* 平台原版这里直接 return（漏了 free）—— 补上，失败路径不该留消息不还 */
        osal_free_msg(OSAL_MSG_BACKUP_SENSER, msg);
        return res;
    }

    memcpy(data, msg, sizeof(struct backup_senser_msg));

    res = osal_free_msg(OSAL_MSG_BACKUP_SENSER, msg);
    if (res)
        return res;

    return 0;
}

static int backup_senser_sync_msg(int function, int mode, void *data, size_t *data_len,
                                  int arg_count, ...)
{
    int res;
    struct backup_senser_msg msg;

    memset(&msg, 0, sizeof(msg));
    msg.type = TYPE_START;

    /* 功能号编码：高 16 位功能、低位固定 0x603（平台原样） */
    int f = (function << 16) | 0x603;
    memcpy(msg.data, &f, sizeof(int));

    va_list ap;
    va_start(ap, arg_count);
    for (int i = 0; i < arg_count; i++)
        ((int *) msg.data)[i + 1] = va_arg(ap, int);
    va_end(ap);

    res = backup_senser_send_msg(&msg);
    if (res)
        return res;

    if (mode == MODE_READ && msg.type == TYPE_DONE && msg.res_len <= *data_len) {
        *data_len = msg.res_len;
        if (msg.res_ptr) {
            /* 数据在对面内存里：按指针取，然后回一条 FREE 让它释放 */
            if (data) {
                res = mem_read(data, msg.res_ptr, msg.res_len);
                if (res)
                    return res;
            }
            msg.type = TYPE_FREE;
            res = backup_senser_send_msg(&msg);
            if (res)
                return res;
            if (msg.type != TYPE_FREE_DONE)
                return msg.err;
        } else {
            /* 内联在消息里（≤0x130 字节的小数据走这条） */
            if (data)
                memcpy(data, msg.read_data, msg.res_len);
        }
    } else if (msg.type == TYPE_ERROR) {
        return msg.err;
    } else {
        return -1;
    }

    return 0;
}

int backup_senser_cmd_preset_data_read(int from_memory, void *data, size_t *len)
{
    return backup_senser_sync_msg(5, MODE_READ, data, len, 1, from_memory);
}
