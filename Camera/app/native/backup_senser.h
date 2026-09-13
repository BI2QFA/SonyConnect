#pragma once

#include <stddef.h>










#define BACKUP_SENSER_PRESET_DATA_MAX_SIZE 0x100000

int backup_senser_cmd_preset_data_read(int from_memory, void *data, size_t *len);
