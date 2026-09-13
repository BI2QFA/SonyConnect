#pragma once

#include <sys/types.h>


#define MEM_DEV "/dev/mem"

int mem_read(void *buffer, off_t ptr, size_t len);
