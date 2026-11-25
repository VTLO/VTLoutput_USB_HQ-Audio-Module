/* vtlo-alsactl: minimal ALSA control (mixer) tool for the VTLoutput_HQ app.
 * list N | get N NUMID | set N NUMID V [V...]   (N = card number, /dev/snd/controlCN)
 * Output of list: one control per line, TAB-separated:
 *   numid iface name type count min max step dbmin_cB dbmax_cB values(comma)
 * type: bool|int|int64|enum|other ; dB values in centi-dB, empty when the driver gives no TLV. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sound/asound.h>

static const char *typ(int t) {
    switch (t) {
        case SNDRV_CTL_ELEM_TYPE_BOOLEAN: return "bool";
        case SNDRV_CTL_ELEM_TYPE_INTEGER: return "int";
        case SNDRV_CTL_ELEM_TYPE_INTEGER64: return "int64";
        case SNDRV_CTL_ELEM_TYPE_ENUMERATED: return "enum";
        default: return "other";
    }
}

static int opencard(int n) {
    char p[64];
    snprintf(p, sizeof p, "/dev/snd/controlC%d", n);
    int fd = open(p, O_RDWR);
    if (fd < 0) fd = open(p, O_RDONLY);
    if (fd < 0) fprintf(stderr, "open %s: %s\n", p, strerror(errno));
    return fd;
}

/* dB range in centi-dB for an integer control, from its TLV (returns 1 when known) */
static int dbrange(int fd, unsigned numid, long min, long max, long *dmin, long *dmax) {
    unsigned buf[2 + 64];
    struct snd_ctl_tlv *t = (struct snd_ctl_tlv *)buf;
    memset(buf, 0, sizeof buf);
    t->numid = numid;
    t->length = sizeof(unsigned) * 64;
    if (ioctl(fd, SNDRV_CTL_IOCTL_TLV_READ, t) < 0) return 0;
    if (t->length < 12) return 0;
    unsigned *v = t->tlv;
    switch (v[0]) {
        case 1: { /* DB_SCALE: min, step(low16) in 0.01 dB */
            long step = (int)(v[3] & 0xffff);
            *dmin = (int)v[2];
            *dmax = *dmin + step * (max - min);
            return 1;
        }
        case 4: case 5: /* DB_MINMAX / DB_MINMAX_MUTE */
            *dmin = (int)v[2]; *dmax = (int)v[3];
            return 1;
        default: return 0;
    }
}

static int readvals(int fd, struct snd_ctl_elem_info *inf, long *out, int n) {
    struct snd_ctl_elem_value val;
    memset(&val, 0, sizeof val);
    val.id.numid = inf->id.numid;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &val) < 0) return -1;
    for (int i = 0; i < n && i < 128; i++) {
        if (inf->type == SNDRV_CTL_ELEM_TYPE_BOOLEAN || inf->type == SNDRV_CTL_ELEM_TYPE_INTEGER) out[i] = val.value.integer.value[i];
        else if (inf->type == SNDRV_CTL_ELEM_TYPE_INTEGER64) out[i] = val.value.integer64.value[i];
        else if (inf->type == SNDRV_CTL_ELEM_TYPE_ENUMERATED) out[i] = val.value.enumerated.item[i];
        else out[i] = 0;
    }
    return 0;
}

static int cmd_list(int fd) {
    struct snd_ctl_elem_list l;
    memset(&l, 0, sizeof l);
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &l) < 0) { perror("ELEM_LIST"); return 1; }
    unsigned count = l.count;
    struct snd_ctl_elem_id *ids = calloc(count ? count : 1, sizeof *ids);
    l.offset = 0; l.space = count; l.pids = ids;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, &l) < 0) { perror("ELEM_LIST2"); return 1; }
    for (unsigned i = 0; i < l.used; i++) {
        struct snd_ctl_elem_info inf;
        memset(&inf, 0, sizeof inf);
        inf.id.numid = ids[i].numid;
        if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &inf) < 0) continue;
        long min = 0, max = 0, step = 0, dmin = 0, dmax = 0;
        int hasdb = 0;
        if (inf.type == SNDRV_CTL_ELEM_TYPE_INTEGER) {
            min = inf.value.integer.min; max = inf.value.integer.max; step = inf.value.integer.step;
            hasdb = dbrange(fd, inf.id.numid, min, max, &dmin, &dmax);
        } else if (inf.type == SNDRV_CTL_ELEM_TYPE_BOOLEAN) { min = 0; max = 1; step = 1; }
        long vals[128];
        int n = inf.count > 128 ? 128 : (int)inf.count;
        printf("%u\t%d\t%s\t%s\t%u\t%ld\t%ld\t%ld\t", inf.id.numid, (int)inf.id.iface, inf.id.name, typ(inf.type), inf.count, min, max, step);
        if (hasdb) printf("%ld\t%ld\t", dmin, dmax); else printf("\t\t");
        if (readvals(fd, &inf, vals, n) == 0) for (int k = 0; k < n; k++) printf(k ? ",%ld" : "%ld", vals[k]);
        printf("\n");
    }
    free(ids);
    return 0;
}

static int cmd_get(int fd, unsigned numid) {
    struct snd_ctl_elem_info inf;
    memset(&inf, 0, sizeof inf);
    inf.id.numid = numid;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &inf) < 0) { perror("ELEM_INFO"); return 1; }
    long vals[128];
    int n = inf.count > 128 ? 128 : (int)inf.count;
    if (readvals(fd, &inf, vals, n) < 0) { perror("ELEM_READ"); return 1; }
    for (int k = 0; k < n; k++) printf(k ? ",%ld" : "%ld", vals[k]);
    printf("\n");
    return 0;
}

static int cmd_set(int fd, unsigned numid, int nv, char **v) {
    struct snd_ctl_elem_info inf;
    memset(&inf, 0, sizeof inf);
    inf.id.numid = numid;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, &inf) < 0) { perror("ELEM_INFO"); return 1; }
    if (inf.type != SNDRV_CTL_ELEM_TYPE_BOOLEAN && inf.type != SNDRV_CTL_ELEM_TYPE_INTEGER) {
        fprintf(stderr, "only bool/int controls can be set\n"); return 2;
    }
    struct snd_ctl_elem_value val;
    memset(&val, 0, sizeof val);
    val.id.numid = numid;
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, &val) < 0) { perror("ELEM_READ"); return 1; }
    for (unsigned i = 0; i < inf.count && i < 128; i++) {
        long x = strtol(v[nv == 1 ? 0 : (i < (unsigned)nv ? i : nv - 1)], NULL, 10);
        if (inf.type == SNDRV_CTL_ELEM_TYPE_INTEGER) {
            if (x < inf.value.integer.min) x = inf.value.integer.min;
            if (x > inf.value.integer.max) x = inf.value.integer.max;
        } else x = x ? 1 : 0;
        val.value.integer.value[i] = x;
    }
    if (ioctl(fd, SNDRV_CTL_IOCTL_ELEM_WRITE, &val) < 0) { perror("ELEM_WRITE"); return 1; }
    return cmd_get(fd, numid);
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s list N | get N NUMID | set N NUMID V...\n", argv[0]); return 2; }
    int fd = opencard(atoi(argv[2]));
    if (fd < 0) return 1;
    int r = 2;
    if (!strcmp(argv[1], "list")) r = cmd_list(fd);
    else if (!strcmp(argv[1], "get") && argc >= 4) r = cmd_get(fd, (unsigned)atoi(argv[3]));
    else if (!strcmp(argv[1], "set") && argc >= 5) r = cmd_set(fd, (unsigned)atoi(argv[3]), argc - 4, argv + 4);
    else fprintf(stderr, "bad arguments\n");
    close(fd);
    return r;
}
