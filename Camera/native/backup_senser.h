#pragma once

#include <stddef.h>

/*
 * Backup 预设数据（preset data）读取。
 *
 * 渠道与 backup.h 那条**不是同一个**：这里走 OSAL 消息 BACKUP_SENSER = 0x3E0166，
 * 功能号 5 = preset_data_read、模式 1 = 从（BIONZ 侧）内存读。
 * OpenMemories-Tweak 的「Backup region」正是这一条。
 *
 * 原样移植自 OpenMemories-Platform drivers/backup_senser.h + .c（只保留只读所需分支）。
 */
#define BACKUP_SENSER_PRESET_DATA_MAX_SIZE 0x100000

int backup_senser_cmd_preset_data_read(int from_memory, void *data, size_t *len);
