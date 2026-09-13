







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
