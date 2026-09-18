#pragma once

#include <sys/types.h>

/* 相机内核暴露的物理内存设备（OpenMemories-Platform drivers/mem.h 同款） */
#define MEM_DEV "/dev/mem"

int mem_read(void *buffer, off_t ptr, size_t len);
