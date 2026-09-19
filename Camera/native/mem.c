/*
 * 物理内存读取（原样移植自 OpenMemories-Platform drivers/mem.c，只留只读那半）。
 *
 * 用途：preset data 有 881121 字节，远超 OSAL 消息里 0x130 字节的内联缓冲，
 * 所以 BIONZ 侧会回一个**指针**（msg.res_ptr），由这里按页对齐 mmap /dev/mem 取回。
 * 也就是说这条读路最终依赖 /dev/mem 可读 —— OpenMemories-Tweak 在本机能取到值，
 * 说明该机器上它是可读的；万一不可读，open/mmap 失败会返回 -1，上层照原样放弃。
 */
#include <fcntl.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "mem.h"

int mem_read(void *buffer, off_t ptr, size_t len)
{
    int res;
    off_t mapped_offset = ptr & ~(sysconf(_SC_PAGESIZE) - 1);
    size_t mapped_len = len + ptr - mapped_offset;

    int fd = open(MEM_DEV, O_RDONLY, 0);
    if (fd == -1)
        return -1;

    char *mapped = (char *) mmap(NULL, mapped_len, PROT_READ, MAP_SHARED, fd, mapped_offset);
    if (mapped == MAP_FAILED) {
        close(fd);
        return -1;
    }

    memcpy(buffer, mapped + ptr - mapped_offset, len);

    res = munmap(mapped, mapped_len);
    if (res)
        return res;

    res = close(fd);
    if (res)
        return res;

    return 0;
}
