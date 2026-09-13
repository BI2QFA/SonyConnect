/* 空 stub：只为通过链接；运行时由相机 /system/lib/libosal_uipc.so 真库解析
   （OpenMemories-Platform stubs/osal_uipc.S 的同名手法，仅保留用到的符号） */
.eabi_attribute Tag_ABI_align_preserved, 1

.macro T name
  .global \name
  .type \name, "function"
  \name:
.endm

T osal_free_msg
T osal_snd_sync_msg
T osal_valloc_msg_wait
