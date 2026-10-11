/* SPDX-License-Identifier: GPL-2.0-or-later
 * Replacement for QEMU helper_phaddd_xmm(CPUX86State*, Reg*, Reg*).
 * Load both original operands before storing, including when d == s.
 * Baseline SSE2; unsigned 32-bit wraparound matches PHADDD semantics.
 */
.text
.globl androidagent_phaddd_fixed
.type androidagent_phaddd_fixed,@function
androidagent_phaddd_fixed:
    movdqu (%rsi), %xmm0
    movdqu (%rdx), %xmm1
    movdqa %xmm0, %xmm2
    pshufd $0xb1, %xmm0, %xmm0
    paddd %xmm2, %xmm0
    pshufd $0xb1, %xmm1, %xmm2
    paddd %xmm2, %xmm1
    shufps $0x88, %xmm1, %xmm0
    movdqu %xmm0, (%rsi)
    ret
.size androidagent_phaddd_fixed, .-androidagent_phaddd_fixed
.section .note.GNU-stack,"",@progbits
