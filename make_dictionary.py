"""
TaukiTauki dictionary + bigram database builder.

Strategy
--------
1. Build a canonical word set from hiztegi_batua.json by generating morphological
   forms (declensions + flexions) for each entry according to its POS tags.
2. Load proper_names.json and add capitalised forms to the word set.
3. Load word_freq.json and keep ONLY entries whose lowercase form is already in
   the canonical set. Dropped words are logged to data/dropped_words.log.
4. Merge: canonical words get a base frequency of 2; words that survive the
   frequency filter get their actual corpus frequency (overrides the base).
5. Load bigram_freq.json, apply --min-bigram-freq threshold, and restrict to
   pairs where BOTH words are in the final word set.
6. Load emoji.json.
7. Write everything to app/src/main/assets/dict.db.

Outputs
-------
  app/src/main/assets/dict.db
  data/dropped_words.log

Tables
------
  words   (word TEXT PK, frequency INTEGER, is_proper INTEGER DEFAULT 0)
  bigrams (prev_word TEXT, next_word TEXT, frequency INTEGER)
  emojis  (name TEXT PK, emoji TEXT)

CLI
---
  --min-freq N          Minimum corpus frequency for word_freq.json entries (default 2)
  --min-bigram-freq N   Minimum frequency for bigram_freq.json entries (default 3)
  --no-dict             Skip hiztegi_batua.json (use word_freq.json only, no filtering)
"""

import sqlite3
import json
import re
import argparse
import logging
from pathlib import Path
from collections import defaultdict


# ─── Logging ──────────────────────────────────────────────────────────────────

def setup_logging(log_path: Path) -> logging.Logger:
    logger = logging.getLogger("make_dictionary")
    logger.setLevel(logging.DEBUG)
    fh = logging.FileHandler(log_path, encoding="utf-8", mode="w")
    fh.setLevel(logging.DEBUG)
    ch = logging.StreamHandler()
    ch.setLevel(logging.INFO)
    fmt = logging.Formatter("%(message)s")
    fh.setFormatter(fmt); ch.setFormatter(fmt)
    logger.addHandler(fh); logger.addHandler(ch)
    return logger


# ─── Morphology ───────────────────────────────────────────────────────────────
# Each function takes the lemma string and returns a set of inflected forms.

def _ends_vowel(w): return w and w[-1].lower() in "aeiouü"
def _ends_cons(w):  return w and w[-1].lower() not in "aeiouü"


def inflect_noun(lemma: str) -> set:
    """Basque noun declension (absolutive, ergative, dative, genitive, locative,
    ablative, instrumental, comitative, destinative — singular and plural)."""
    w = lemma
    forms = {w}
    v = _ends_vowel(w)

    # Singular determinate
    stem_sg = w if v else w          # base for suffixing
    art = "a" if v else "a"          # definite article always -a after cons, vowel+a→-a
    # After vowel-final lemma the article merges: etxe+a = etxea
    sg = (w + "a") if not v else (w + "a")   # both cases: +a
    forms.add(sg)                                  # etxea / mendía
    forms.add(sg + "k")                            # erg sg
    forms.add(sg[:-1] + "ari" if v else w + "ari") # dat sg  (etxeari / mendiari)
    forms.add(sg[:-1] + "aren" if v else w + "aren")# gen sg
    forms.add(sg[:-1] + "an" if v else w + "ean")  # ine sg
    forms.add(sg[:-1] + "tik" if v else w + "etik") # abl sg
    forms.add(sg[:-1] + "ra" if v else w + "era")  # all sg
    forms.add(sg[:-1] + "z" if v else w + "ez")    # ins sg
    forms.add(sg[:-1] + "rekin" if v else w + "ekin") # com sg
    forms.add(sg[:-1] + "rentzat" if v else w + "entzat") # dest sg

    # Plural determinate
    pl = (w + "ak") if not v else (w + "ak")
    forms.add(pl)                                  # etxeak / mendiak
    forms.add(w + "ei" if v else w + "ei")         # dat pl
    forms.add(w + "en" if v else w + "en")         # gen pl (etxeen/mendien)
    forms.add(w + "etan" if v else w + "etan")     # ine pl
    forms.add(w + "etatik" if v else w + "etatik") # abl pl
    forms.add(w + "etara" if v else w + "etara")   # all pl
    forms.add(w + "ez" if v else w + "ez")         # ins pl
    forms.add(w + "ekin" if v else w + "ekin")     # com pl
    forms.add(w + "entzat" if v else w + "entzat") # dest pl

    # Indefinite (partitive / bare stem used in negation, etc.)
    if not v:
        forms.add(w + "ik")   # partitive
        forms.add(w + "ek")   # erg indef

    return forms


def inflect_adjective(lemma: str) -> set:
    """Basque adjectives decline like nouns when used predicatively."""
    return inflect_noun(lemma)


def inflect_verb(lemma: str) -> set:
    """Verbal nouns: infinitive + common nominalisations and aspect forms."""
    w = lemma
    forms = {w}

    if w.endswith("tu") or w.endswith("du"):
        root = w[:-2]
        forms.update([
            root + "tzen",    # habitual/imperfective aspect
            root + "tze",     # verbal noun (process)
            root + "tzea",    # verbal noun (object form)
            root + "tzeko",   # purposive
            root + "tzean",   # temporal/locative
            root + "tzeko",   # to-infinitive
            root + "tzaile",  # agent noun (one who …s)
            root + "tzailea",
            root + "tzaileak",
            w + "ko",         # future/prospective
            w + "a",          # nominalised
            w + "ak",
        ])
    elif w.endswith("n"):
        root = w[:-1]
        forms.update([
            root + "ten",
            root + "te",
            root + "tea",
            root + "teko",
            root + "tean",
            root + "tzaile",
            root + "tzailea",
            w + "go",
            w + "a",
            w + "ak",
        ])
    elif w.endswith("i"):
        root = w[:-1]
        forms.update([
            root + "ten",
            root + "te",
            root + "tea",
            root + "teko",
            root + "tean",
            w + "ko",
            w + "a",
        ])
    elif w.endswith("o"):
        forms.update([w + "a", w + "ak", w + "ko"])

    return forms


def inflect_adverb(lemma: str) -> set:
    """Adverbs mostly uninflected; add common postpositional combinations."""
    w = lemma
    return {w, w + "ago", w + "egi", w + "en"}


def inflect_determiner(lemma: str) -> set:
    """Determiners / pronouns — small closed class, just add the lemma."""
    return {lemma}


def inflect_postposition(lemma: str) -> set:
    return {lemma}


# POS tag → inflection function mapping.
# hiztegi_batua POS tags contain substrings like "iz.", "adj.", "ad.", "adb.", etc.
POS_INFLECTORS = [
    (["iz."],          inflect_noun),
    (["adj.", "ord."], inflect_adjective),
    (["ad."],          inflect_verb),
    (["adb."],         inflect_adverb),
    (["det.", "izkb."],inflect_determiner),
    (["posp.", "lok."], inflect_postposition),
]


def forms_for_entry(lemma: str, pos_tags: set) -> set:
    """Return all morphological forms for a lemma given its POS tags."""
    forms = {lemma}
    matched = False
    for triggers, fn in POS_INFLECTORS:
        if any(any(t in p for t in triggers) for p in pos_tags):
            forms |= fn(lemma)
            matched = True
    if not matched:
        # Unknown POS: at minimum add noun-like forms (most common case)
        forms |= inflect_noun(lemma)
    return forms


# ─── Dictionary processing ────────────────────────────────────────────────────

def process_hiztegi_json(filepath: Path, logger: logging.Logger) -> dict:
    """Returns {form: base_frequency=2} for all generated morphological forms."""
    if not filepath.exists():
        logger.warning(f"Dictionary not found at {filepath}")
        return {}

    with open(filepath, encoding="utf-8") as f:
        data = json.load(f)

    word_set: dict[str, int] = {}
    # also more than one letter
    single_word_pattern = re.compile(r"^[\w][\w'-]+$", re.UNICODE)

    def extract_pos(adierak_list):
        return {pos for adiera in adierak_list for pos in adiera.get("pos", [])}

    def process_entry(title: str, adierak: list):
        if not single_word_pattern.match(title):
            return
        pos_tags = extract_pos(adierak)
        for form in forms_for_entry(title, pos_tags):
            word_set[form.lower()] = 2   # base frequency; corpus freq will override

    total = 0
    for item in data:
        process_entry(item.get("title", ""), item.get("adierak", []))
        for sub in item.get("subs", []):
            process_entry(sub.get("title", ""), sub.get("adierak", []))
        total += 1

    logger.info(f"Dictionary: processed {total:,} entries → {len(word_set):,} forms")
    return word_set


# ─── Proper names ─────────────────────────────────────────────────────────────

def load_proper_names(filepath: Path, logger: logging.Logger) -> dict:
    """
    Returns {name_lowercase: (display_form, frequency=10)} for all proper names.
    The display_form preserves the original capitalisation from the JSON.
    """
    if not filepath.exists():
        logger.info(f"No proper_names.json at {filepath}; skipping.")
        return {}

    with open(filepath, encoding="utf-8") as f:
        raw = json.load(f)

    names: dict[str, tuple[str, int]] = {}
    for key, value in raw.items():
        if key.startswith("_"):
            continue
        items = value if isinstance(value, list) else []
        for name in items:
            name = name.strip()
            if name:
                # Multi-word names (e.g. "Eusko Jaurlaritza") are stored as-is.
                names[name.lower()] = (name, 10)

    logger.info(f"Proper names: loaded {len(names):,} entries")
    return names


# ─── Word frequency filtering ─────────────────────────────────────────────────

def filter_word_freq(
    freq_path: Path,
    canonical: set,
    min_freq: int,
    logger: logging.Logger,
) -> dict:
    """
    Loads word_freq.json and returns only words whose lowercase form is in
    `canonical`. Words that are dropped are written to the log.
    Returns {word: frequency}.
    """
    if not freq_path.exists():
        logger.info(f"No word_freq.json at {freq_path}; skipping corpus frequencies.")
        return {}

    with open(freq_path, encoding="utf-8") as f:
        raw = json.load(f)

    kept: dict[str, int] = {}
    dropped_below_thresh = 0
    dropped_not_in_dict: list[str] = []

    for word, freq in raw.items():
        freq = int(freq)
        if freq < min_freq:
            dropped_below_thresh += 1
            continue
        lw = word.lower()
        if lw in canonical:
            kept[lw] = max(kept.get(lw, 0), freq)
        else:
            dropped_not_in_dict.append(word)

    # Log dropped words
    if dropped_not_in_dict:
        logger.debug(
            f"\n=== Words dropped (not in dictionary) [{len(dropped_not_in_dict):,}] ===\n"
            + "\n".join(sorted(dropped_not_in_dict))
        )

    logger.info(
        f"word_freq.json: {len(raw):,} entries → "
        f"kept {len(kept):,}, "
        f"dropped {len(dropped_not_in_dict):,} (not in dict), "
        f"{dropped_below_thresh:,} (below min-freq {min_freq})"
    )
    return kept


# ─── Bigram loading ───────────────────────────────────────────────────────────

def load_bigrams(
    filepath: Path,
    word_set: set,
    min_freq: int,
    logger: logging.Logger,
) -> list:
    """
    Loads bigram_freq.json (format A or B), applies min_freq threshold, and
    restricts to pairs where BOTH words are in word_set.
    Returns list of (prev_word, next_word, frequency) tuples.
    """
    if not filepath.exists():
        logger.info(f"No bigram_freq.json at {filepath}; skipping bigrams.")
        return []

    with open(filepath, encoding="utf-8") as f:
        raw = json.load(f)

    raw_bigrams = []
    if isinstance(raw, dict):
        for key, freq in raw.items():
            parts = key.strip().split()
            if len(parts) == 2:
                raw_bigrams.append((parts[0].lower(), parts[1].lower(), int(freq)))
            else:
                logger.debug(f"  Skipping malformed bigram key: {key!r}")
    elif isinstance(raw, list):
        for entry in raw:
            prev = entry.get("prev", "").strip().lower()
            nxt  = entry.get("next", "").strip().lower()
            freq = int(entry.get("freq", entry.get("frequency", 1)))
            if prev and nxt:
                raw_bigrams.append((prev, nxt, freq))
    else:
        logger.warning("Unrecognised bigram_freq.json format; skipping.")
        return []

    kept = []
    dropped_freq = 0
    dropped_oov  = 0
    oov_examples = []

    for prev, nxt, freq in raw_bigrams:
        if freq < min_freq:
            dropped_freq += 1
            continue
        if prev not in word_set or nxt not in word_set:
            dropped_oov += 1
            if len(oov_examples) < 200:
                missing = [w for w in (prev, nxt) if w not in word_set]
                oov_examples.append(f"  {prev} {nxt} (freq={freq}, oov={missing})")
            continue
        kept.append((prev, nxt, freq))

    if oov_examples:
        logger.debug(
            f"\n=== Bigrams dropped (OOV words) [{dropped_oov:,} total, showing first {len(oov_examples)}] ===\n"
            + "\n".join(oov_examples)
        )

    logger.info(
        f"bigrams: {len(raw_bigrams):,} raw → "
        f"kept {len(kept):,}, "
        f"dropped {dropped_freq:,} (below min-bigram-freq {min_freq}), "
        f"{dropped_oov:,} (OOV)"
    )
    return kept


# ─── Emoji loading ────────────────────────────────────────────────────────────

def load_emojis(filepath: Path, logger: logging.Logger) -> list:
    """Loads emoji.json → list of (name_lowercase, emoji) tuples."""
    if not filepath.exists():
        logger.info(f"No emoji.json at {filepath}; skipping emoji table.")
        return []
    with open(filepath, encoding="utf-8") as f:
        raw = json.load(f)
    if not isinstance(raw, dict):
        logger.warning("emoji.json must be a dict of name→emoji; skipping.")
        return []
    entries = [
        (name.strip().lower(), emoji.strip())
        for name, emoji in raw.items()
        if name.strip() and emoji.strip()
    ]
    logger.info(f"Emojis: loaded {len(entries):,} entries")
    return entries


# ─── Database builder ─────────────────────────────────────────────────────────

def generate_db(min_freq: int, min_bigram_freq: int, use_dict: bool):
    root         = Path(__file__).parent
    dict_path    = root / "data" / "hiztegi_batua.json"
    freq_path    = root / "data" / "word_freq.json"
    bigram_path  = root / "data" / "bigram_freq.json"
    emoji_path   = root / "data" / "emoji.json"
    names_path   = root / "data" / "proper_names.json"
    db_path      = root / "app" / "src" / "main" / "assets" / "dict.db"
    log_path     = root / "data" / "dropped_words.log"

    db_path.parent.mkdir(parents=True, exist_ok=True)
    logger = setup_logging(log_path)
    logger.info(f"=== TaukiTauki make_dictionary ===")
    logger.info(f"  min-freq={min_freq}  min-bigram-freq={min_bigram_freq}  use-dict={use_dict}")

    # ── Step 1: canonical word set from dictionary ───────────────────────────
    if use_dict:
        dict_words = process_hiztegi_json(dict_path, logger)
    else:
        logger.info("Skipping dictionary (--no-dict active)")
        dict_words = {}

    canonical: set[str] = set(dict_words.keys())   # lowercase forms

    # ── Step 2: proper names ─────────────────────────────────────────────────
    proper_names = load_proper_names(names_path, logger)
    # Add lowercased proper name forms to canonical so bigram filtering
    # doesn't drop sentences starting with names.
    for lw in proper_names:
        canonical.add(lw)

    # ── Step 3: filter corpus frequencies ───────────────────────────────────
    if use_dict:
        freq_words = filter_word_freq(freq_path, canonical, min_freq, logger)
    else:
        # Without dictionary filter, accept everything above threshold
        if freq_path.exists():
            with open(freq_path, encoding="utf-8") as f:
                raw = json.load(f)
            freq_words = {w.lower(): int(c) for w, c in raw.items() if int(c) >= min_freq}
            logger.info(f"word_freq (no-dict mode): kept {len(freq_words):,} entries")
        else:
            freq_words = {}

    # ── Step 4: merge frequencies ────────────────────────────────────────────
    # Start with base-frequency canonical forms, then override with corpus counts.
    master: dict[str, int] = dict(dict_words)   # {form: 2}
    master: dict[str, int] = dict()
    master.update(freq_words)                    # corpus counts win

    # ── Step 5: final word set for bigram filtering ──────────────────────────
    final_word_set: set[str] = set(master.keys()) | set(proper_names.keys())

    # ── Step 6: bigrams ──────────────────────────────────────────────────────
    bigrams = load_bigrams(bigram_path, final_word_set, min_bigram_freq, logger)

    # ── Step 7: emojis ───────────────────────────────────────────────────────
    emojis = load_emojis(emoji_path, logger)

    # ── Step 8: write SQLite ─────────────────────────────────────────────────
    logger.info(f"\nWriting database to {db_path}…")
    conn   = sqlite3.connect(db_path)
    cursor = conn.cursor()

    # words table — added is_proper column
    cursor.execute("DROP TABLE IF EXISTS words")
    cursor.execute(
        "CREATE TABLE words ("
        "  word TEXT PRIMARY KEY,"
        "  frequency INTEGER NOT NULL,"
        "  is_proper INTEGER NOT NULL DEFAULT 0"
        ")"
    )
    cursor.execute("CREATE INDEX idx_word_prefix ON words(word)")

    word_rows = [(w, f, 0) for w, f in master.items()]
    # Add proper names with display capitalisation; if already present, upgrade is_proper
    proper_rows = [(display, freq, 1) for lw, (display, freq) in proper_names.items()]

    logger.info(f"  Inserting {len(word_rows):,} common words…")
    cursor.executemany("INSERT OR REPLACE INTO words VALUES (?, ?, ?)", word_rows)
    logger.info(f"  Inserting {len(proper_rows):,} proper names…")
    cursor.executemany(
        "INSERT INTO words VALUES (?, ?, ?) "
        "ON CONFLICT(word) DO UPDATE SET frequency=excluded.frequency, is_proper=1",
        proper_rows
    )

    # bigrams table
    cursor.execute("DROP TABLE IF EXISTS bigrams")
    cursor.execute(
        "CREATE TABLE bigrams ("
        "  prev_word TEXT NOT NULL,"
        "  next_word TEXT NOT NULL,"
        "  frequency INTEGER NOT NULL,"
        "  PRIMARY KEY (prev_word, next_word)"
        ")"
    )
    cursor.execute("CREATE INDEX idx_bigram_prev ON bigrams(prev_word)")
    if bigrams:
        logger.info(f"  Inserting {len(bigrams):,} bigrams…")
        cursor.executemany(
            "INSERT OR REPLACE INTO bigrams VALUES (?, ?, ?)", bigrams
        )

    # emojis table
    cursor.execute("DROP TABLE IF EXISTS emojis")
    cursor.execute("CREATE TABLE emojis (name TEXT PRIMARY KEY, emoji TEXT NOT NULL)")
    cursor.execute("CREATE INDEX idx_emoji_name ON emojis(name)")
    if emojis:
        logger.info(f"  Inserting {len(emojis):,} emojis…")
        cursor.executemany("INSERT OR REPLACE INTO emojis VALUES (?, ?)", emojis)

    conn.commit()
    conn.close()

    logger.info(f"\nDone. Log written to {log_path}")


# ─── CLI ──────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="TaukiTauki Dictionary DB Generator",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--min-freq", type=int, default=2,
        help="Minimum corpus frequency; word_freq.json entries below this are dropped"
    )
    parser.add_argument(
        "--min-bigram-freq", type=int, default=3,
        help="Minimum frequency for bigrams; entries below this are dropped"
    )
    parser.add_argument(
        "--no-dict", action="store_false", dest="use_dict",
        help="Skip hiztegi_batua.json; disable dictionary filtering of word_freq"
    )
    parser.set_defaults(use_dict=True)
    args = parser.parse_args()
    generate_db(args.min_freq, args.min_bigram_freq, args.use_dict)
