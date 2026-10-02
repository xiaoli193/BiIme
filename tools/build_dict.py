#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 CC-CEDICT 中英词典 + jieba 词频表编译成输入法用的紧凑资源 pinyin.dict。

输出每行 7 个字段（\t 分隔），整表按第 1 列字典序排序：

    key \t 简体 \t 繁体 \t 带声调拼音 \t 英文释义 \t 音节数 \t 词频分

key = 无声调小写拼音（ü 写作 v），例如 "ni3 hao3" -> "nihao"。
词频分越小越常用（来自 jieba dict.txt 的词频，未收录的词给一个很大的值）。

除了 CC-CEDICT 的词条，还会用 jieba 词频表补一批「派生词条」：
CC-CEDICT 缺很多常用短语（我是 / 我爱你 / 你好吗 …），这些词按
「每个字的读音」拼出拼音、按「每个字的释义」拼出英文，例如

    woshi   我是   wo3 shi4   I + to be

为了不串味，派生时只用读音唯一的汉字（的、了、行、和 这类多音字一律跳过）。

用法:
    python3 tools/build_dict.py \
        --cedict /root/dict/cedict.txt \
        --freq    /root/dict/jieba_dict.txt \
        --out     app/assets/pinyin.dict
"""

import argparse
import io
import os
import re
import sys

CEDICT_RE = re.compile(r'^(\S+)\s+(\S+)\s+\[([^\]]+)\]\s+/(.*)/\s*$')
CJK_RE = re.compile(r'^[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]+$')
TONE_MAP = str.maketrans({
    'ā': 'a', 'á': 'a', 'ǎ': 'a', 'à': 'a',
    'ē': 'e', 'é': 'e', 'ě': 'e', 'è': 'e',
    'ī': 'i', 'í': 'i', 'ǐ': 'i', 'ì': 'i',
    'ō': 'o', 'ó': 'o', 'ǒ': 'o', 'ò': 'o',
    'ū': 'u', 'ú': 'u', 'ǔ': 'u', 'ù': 'u',
    'ǖ': 'v', 'ǘ': 'v', 'ǚ': 'v', 'ǜ': 'v', 'ü': 'v',
    'ń': 'n', 'ň': 'n', 'ǹ': 'n', 'ḿ': 'm',
})

MAX_LINES_PER_KEY = 12
MAX_SIMP_LEN = 6
MAX_KEY_LEN = 20
UNKNOWN_RANK = 3000000
DERIVE_PENALTY = 30000          # 派生词条让位给词典真词条

# 这些义项对输入法候选没有价值（“variant of X”“surname X”之类），
# 只有它们时整条丢掉，混在真实义项里则降权。
JUNK_PREFIXES = (
    'variant of', 'old variant of', 'see ', 'surname ', 'abbr. for',
    'used in', 'Taiwan pr.', 'Japanese variant', 'erhua variant',
    'also written', 'Japanese kokuji', 'kana ', 'phonetic ',
)

# jieba 词性标注：人名/地名/机构/其它专名，派生时不要
PROPER_NOUN_POS = ('nr', 'ns', 'nt', 'nz', 'nrt', 'nrf', 'nrj', 'nrl', 'nh', 'nl', 'ng')


def toneless(pinyin):
    """'ni3 hao5' -> ('nihao', 2)。"""
    syllables = []
    for part in pinyin.replace('u:', 'v').replace('U:', 'V').split():
        s = part.strip().lower()
        s = ''.join(ch for ch in s if not ch.isdigit())
        s = s.translate(TONE_MAP)
        if s:
            syllables.append(s)
    return ''.join(syllables), len(syllables)


def is_junk(sense):
    low = sense.lower()
    for p in JUNK_PREFIXES:
        if low.startswith(p.lower()):
            return True
    return False


def gloss_for_display(gloss, max_senses=3, max_len=56):
    """从 '/a/b/CL:..[x]/' 里取前几个真实义项拼成一行展示文本。

    返回 (文本, 被丢掉的虚义项数)；返回 None 表示这条只有“variant of”之类，
    调用方应整条丢弃。
    """
    senses = []
    junk = 0
    for p in gloss.split('/'):
        p = p.strip()
        if not p or p.startswith('CL:') or p.startswith('see '):
            continue
        if is_junk(p):
            junk += 1
            continue
        senses.append(p)
        if len(senses) >= max_senses:
            break
    if not senses:
        return None
    text = '; '.join(senses)
    if len(text) > max_len:
        text = text[:max_len - 1] + '…'
    return text, junk


def load_freq(path):
    freq = {}
    with open(path, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            parts = line.split()
            if len(parts) < 2:
                continue
            word = parts[0]
            try:
                n = int(parts[1])
            except ValueError:
                continue
            if word not in freq or n > freq[word]:
                freq[word] = n
    return freq


def rank_of(word, freq):
    n = freq.get(word)
    if n is None or n <= 0:
        return UNKNOWN_RANK + len(word) * 1000
    if n > 2000000:
        n = 2000000
    return 2000000 - n


def read_cedict(path, freq):
    """返回 (词条列表, 单字信息, 单字读音集合, 已收录汉字词集合)。"""
    rows = []
    char_info = {}      # 汉字 -> (rank, 无声调拼音, 带声调拼音, 短释义)
    readings = {}       # 汉字 -> {无声调拼音}
    words = set()       # CC-CEDICT 里已有的词
    skipped = 0
    with open(path, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            m = CEDICT_RE.match(line)
            if not m:
                skipped += 1
                continue
            trad, simp, pinyin, gloss = m.group(1), m.group(2), m.group(3), m.group(4)
            if not CJK_RE.match(simp) or len(simp) > MAX_SIMP_LEN:
                continue
            key, nsyl = toneless(pinyin)
            if not key or len(key) > MAX_KEY_LEN or not key.isalpha():
                continue
            gl = gloss_for_display(gloss)
            if gl is None:
                continue
            display, junk = gl
            if len(simp) == 1 and nsyl != 1:
                continue
            rank = rank_of(simp, freq)
            if nsyl != len(simp):
                rank += 2000
            if junk:
                rank += 400000
            rows.append((key, simp, trad, pinyin, display, nsyl, rank))
            words.add(simp)
            if len(simp) == 1:
                readings.setdefault(simp, set()).add(key)
                short = gloss_for_display(gloss, max_senses=1, max_len=20)
                if short is not None:
                    sr = rank_of(simp, freq)
                    prev = char_info.get(simp)
                    if prev is None or sr < prev[0]:
                        char_info[simp] = (sr, key, pinyin.strip().lower(), short[0])
    return rows, char_info, readings, words, skipped


def derive_rows(freq_path, freq, char_info, ambiguous, words, min_freq, max_rows):
    """用 jieba 词频表补 CC-CEDICT 里缺的常用词（我是 / 我爱你 / 你好吗 …）。"""
    out = []
    if not freq_path or not os.path.exists(freq_path):
        return out
    with open(freq_path, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            parts = line.split()
            if len(parts) < 2:
                continue
            word = parts[0]
            try:
                n = int(parts[1])
            except ValueError:
                continue
            if n < min_freq or len(word) < 2 or len(word) > 4 or not CJK_RE.match(word):
                continue
            if word in words:
                continue
            pos = parts[2] if len(parts) > 2 else ''
            if pos.startswith(PROPER_NOUN_POS):
                continue
            if any((ch not in char_info or ch in ambiguous) for ch in word):
                continue
            key = ''.join(char_info[ch][1] for ch in word)
            if not key or len(key) > MAX_KEY_LEN or not key.isalpha():
                continue
            gloss = ' + '.join(char_info[ch][3] for ch in word)
            if len(gloss) > 56:
                gloss = gloss[:55] + '…'
            toned = ' '.join(char_info[ch][2] for ch in word)
            rank = rank_of(word, freq) + DERIVE_PENALTY
            out.append((key, word, word, toned, gloss, len(word), rank))
            if len(out) >= max_rows:
                break
    return out


def write_assoc(rows, path, max_entries=60000, max_en=28):
    """联想索引：按「中文」排序的词表，用来查「以某个词开头的常用词」。
    只收 2~4 字、且词频已知（jieba 收录）的词条，否则联想质量会很差。"""
    best = {}
    for r in rows:
        simp, gloss, rank = r[1], r[4], r[6]
        if len(simp) < 2 or len(simp) > 4:
            continue
        if rank >= UNKNOWN_RANK:      # 词频未知，不要
            continue
        if not gloss:
            continue
        en = gloss.split(';')[0].strip()
        if len(en) > max_en:
            en = en[:max_en - 1] + '…'
        cur = best.get(simp)
        if cur is None or rank < cur[0]:
            best[simp] = (rank, en)
    items = sorted(best.items(), key=lambda kv: kv[0])
    if len(items) > max_entries:
        # 太多就按词频砍，保留最常用的
        keep = sorted(items, key=lambda kv: kv[1][0])[:max_entries]
        items = sorted(keep, key=lambda kv: kv[0])
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with io.open(path, 'w', encoding='utf-8') as f:
        for word, (rank, en) in items:
            f.write(u'%s\t%d\t%s\n' % (word, rank, en))
    print('  assoc.idx: %d 条联想词，%.2f MB' % (len(items), os.path.getsize(path) / 1048576.0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--cedict', required=True)
    ap.add_argument('--freq', default=None)
    ap.add_argument('--out', required=True)
    ap.add_argument('--assoc', default=None, help='联想索引输出路径（可选）')
    ap.add_argument('--derive-min-freq', type=int, default=200,
                    help='派生词条的最低 jieba 词频，越小补得越多')
    ap.add_argument('--derive-max', type=int, default=40000,
                    help='派生词条数量上限')
    ap.add_argument('--no-derive', action='store_true', help='不生成派生词条')
    args = ap.parse_args()

    freq = load_freq(args.freq) if args.freq and os.path.exists(args.freq) else {}
    if not freq:
        print('警告：没有词频表，候选只能按词长排序', file=sys.stderr)

    rows, char_info, readings, words, skipped = read_cedict(args.cedict, freq)
    cedict_n = len(rows)

    # 多音字（无声调读音不止一种，如 的 de/di、行 xing/hang）不参与派生，避免串味
    ambiguous = set(c for c, s in readings.items() if len(s) > 1)

    derived = []
    if not args.no_derive and freq:
        derived = derive_rows(args.freq, freq, char_info, ambiguous, words,
                              args.derive_min_freq, args.derive_max)

    rows.extend(derived)
    rows.sort(key=lambda r: (r[0], r[6], len(r[1]), r[1]))

    # 同一个 key 下限制条数，优先常用
    out_rows = []
    cur_key = None
    kept = 0
    for r in rows:
        if r[0] != cur_key:
            cur_key = r[0]
            kept = 0
        if kept >= MAX_LINES_PER_KEY:
            continue
        kept += 1
        out_rows.append(r)

    out_rows.sort(key=lambda r: r[0])
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    keys = set()
    with open(args.out, 'w', encoding='utf-8') as f:
        for r in out_rows:
            f.write('%s\t%s\t%s\t%s\t%s\t%d\t%d\n' % r)
            keys.add(r[0])

    if args.assoc:
        write_assoc(rows, args.assoc)

    size = os.path.getsize(args.out)
    print('写出 %s' % args.out)
    print('  CC-CEDICT 词条 %d，派生词条 %d，音节表 %d 个，多音字跳过 %d 个'
          % (cedict_n, len(derived), len(readings), len(ambiguous)))
    print('  最终条目 %d，不同拼音键 %d，跳过无法解析行 %d，体积 %.2f MB'
          % (len(out_rows), len(keys), skipped, size / 1048576.0))


if __name__ == '__main__':
    main()
