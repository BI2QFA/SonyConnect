#pragma once

#define BACKUP_ERROR_INVALID_ARGUMENT 1
#define BACKUP_ERROR_READ_ONLY 3
#define BACKUP_ERROR_INVALID_SUBSYSTEM 4
#define BACKUP_ERROR_WRONG_SIZE 5

/* preset data 内的字段偏移（OpenMemories-Platform drivers/backup.h 同款） */
#define BACKUP_PRESET_DATA_OFFSET_VERSION 0x0c
#define BACKUP_PRESET_DATA_OFFSET_ID1 0x28
#define BACKUP_PRESET_DATA_OFFSET_REGION 0xc0

int Backup_get_datasize(int id);
int Backup_read(int id, void *addr);
