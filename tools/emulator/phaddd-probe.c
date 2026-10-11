/* Independent PHADDD checks: compile for the host and Android x86_64. */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static uint32_t seed = 0x12345678;
static uint32_t next(void) { seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5; return seed; }
static void operation(uint32_t d[4], const uint32_t s[4], int alias) {
    if (alias) {
        __asm__ volatile("movdqu (%0),%%xmm0; phaddd %%xmm0,%%xmm0; movdqu %%xmm0,(%0)" :: "r"(d) : "xmm0", "memory");
    } else {
        __asm__ volatile("movdqu (%0),%%xmm0; movdqu (%1),%%xmm1; phaddd %%xmm1,%%xmm0; movdqu %%xmm0,(%0)" :: "r"(d), "r"(s) : "xmm0", "xmm1", "memory");
    }
}
int main(void) {
    uint32_t simple[4] = {1,2,3,4}; operation(simple, simple, 1);
    printf("PHADDD self: %u %u %u %u; expected 3 7 3 7\n", simple[0], simple[1], simple[2], simple[3]);
    if (simple[0] != 3 || simple[1] != 7 || simple[2] != 3 || simple[3] != 7) return 1;
    for (int alias = 0; alias <= 1; alias++) for (int n = 0; n < 4096; n++) {
        uint32_t a[4], b[4], expected[4];
        for (int j = 0; j < 4; j++) { a[j] = next(); b[j] = next(); }
        expected[0] = a[0] + a[1]; expected[1] = a[2] + a[3];
        expected[2] = alias ? expected[0] : b[0] + b[1]; expected[3] = alias ? expected[1] : b[2] + b[3];
        operation(a, b, alias);
        if (memcmp(a, expected, sizeof(a))) { printf("FAIL case=%d alias=%d\n", n, alias); return 1; }
    }
    puts("PASS 8192 PHADDD vectors, including aliasing and 32-bit overflow"); return 0;
}
