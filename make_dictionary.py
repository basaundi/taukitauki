"""
TaukiTauki dictionary + bigram database builder.

Outputs:  app/src/main/assets/dict.db
Tables:
  words   (word TEXT PK, frequency INTEGER)
  bigrams (prev_word TEXT, next_word TEXT, frequency INTEGER)
           INDEX on prev_word for fast lookup

Inputs (all optional, placed in data/):
  hiztegi_batua.json   – Basque lemma dictionary with declension generation
  word_freq.json       – {"word": count, …}
  bigram_freq.json     – {"prev next": count, …}  OR [{"prev": …, "next": …, "freq": …}, …]
"""

import sqlite3
import json
import re
import argparse
from pathlib import Path


# ─── Declension generation ────────────────────────────────────────────────────

def generate_declensions(word, pos_tags):
    declensions = {word}
    ends_with_vowel = word[-1].lower() in "aeiou"
    for pos in pos_tags:
        if any(tag in pos for tag in ["iz.", "adj.", "ord."]):
            if ends_with_vowel:
                declensions.update([word + "a", word + "ak", word + "ari", word + "an", word + "aren"])
            else:
                declensions.update([word + "a", word + "ak", word + "ari", word + "ean", word + "aren"])
        elif "ad." in pos:
            if word.endswith(("tu", "du")):
                declensions.update([word[:-2] + "tzen", word + "ko"])
            elif word.endswith("n"):
                declensions.update([word[:-1] + "ten", word + "go"])
            elif word.endswith("i"):
                declensions.update([word[:-1] + "ten", word + "ko"])
    return list(declensions)


def process_hiztegi_json(filepath):
    if not filepath.exists():
        print(f"Warning: Dictionary not found at {filepath}")
        return {}
    with open(filepath, encoding="utf-8") as f:
        data = json.load(f)
    words_dict = {}
    single_word_pattern = re.compile(r"^[\w][\w-]*$", re.UNICODE)

    def extract_pos(adierak_list):
        return {pos for adiera in adierak_list for pos in adiera.get("pos", [])}

    def process_entry(title, adierak):
        if single_word_pattern.match(title):
            pos_tags = extract_pos(adierak)
            for form in generate_declensions(title, pos_tags):
                words_dict[form] = 2

    print("Parsing dictionary and generating declensions…")
    for item in data:
        process_entry(item.get("title", ""), item.get("adierak", []))
        for sub in item.get("subs", []):
            process_entry(sub.get("title", ""), sub.get("adierak", []))
    return words_dict


# ─── Bigram loading ───────────────────────────────────────────────────────────

def load_bigrams(filepath):
    """
    Accepts two formats:

    Format A – dict keyed by "prev next":
        {"etxe bat": 42, "bat eta": 17, …}

    Format B – list of objects:
        [{"prev": "etxe", "next": "bat", "freq": 42}, …]

    Returns list of (prev_word, next_word, frequency) tuples.
    """
    if not filepath.exists():
        print(f"Warning: Bigram file not found at {filepath}; skipping bigrams.")
        return []

    with open(filepath, encoding="utf-8") as f:
        raw = json.load(f)

    bigrams = []
    if isinstance(raw, dict):
        for key, freq in raw.items():
            parts = key.strip().split()
            if len(parts) == 2:
                bigrams.append((parts[0].lower(), parts[1].lower(), int(freq)))
            else:
                print(f"  Skipping malformed bigram key: {key!r}")
    elif isinstance(raw, list):
        for entry in raw:
            prev = entry.get("prev", "").strip().lower()
            nxt  = entry.get("next", "").strip().lower()
            freq = int(entry.get("freq", entry.get("frequency", 1)))
            if prev and nxt:
                bigrams.append((prev, nxt, freq))
    else:
        print("Warning: Unrecognised bigram_freq.json format; skipping.")

    print(f"Loaded {len(bigrams):,} bigram entries.")
    return bigrams


# ─── Database builder ─────────────────────────────────────────────────────────

def generate_db(min_freq, use_dict):
    root        = Path(__file__).parent
    json_path   = root / "data" / "hiztegi_batua.json"
    freq_path   = root / "data" / "word_freq.json"
    bigram_path = root / "data" / "bigram_freq.json"
    db_path     = root / "app" / "src" / "main" / "assets" / "dict.db"

    db_path.parent.mkdir(parents=True, exist_ok=True)

    # 1. Unigrams from dictionary
    master_words = process_hiztegi_json(json_path) if use_dict else {}
    if not use_dict:
        print("Skipping dictionary processing (--no-dict active).")

    # 2. Unigrams from frequency file
    if freq_path.exists():
        print(f"Processing frequencies from {freq_path} (min threshold: {min_freq})…")
        with open(freq_path, encoding="utf-8") as f:
            raw_freqs = json.load(f)
        master_words.update({w: freq for w, freq in raw_freqs.items() if freq >= min_freq})
    else:
        print(f"Info: No word_freq.json at {freq_path}; relying on dictionary only.")
        if not use_dict:
            return

    # 3. Bigrams
    bigrams = load_bigrams(bigram_path)

    # 4. Write SQLite
    conn   = sqlite3.connect(db_path)
    cursor = conn.cursor()

    # Words table
    cursor.execute("DROP TABLE IF EXISTS words")
    cursor.execute("CREATE TABLE words (word TEXT PRIMARY KEY, frequency INTEGER)")
    cursor.execute("CREATE INDEX idx_word_prefix ON words(word)")
    word_rows = list(master_words.items())
    print(f"Inserting {len(word_rows):,} word records…")
    cursor.executemany("INSERT INTO words (word, frequency) VALUES (?, ?)", word_rows)

    # Bigrams table
    cursor.execute("DROP TABLE IF EXISTS bigrams")
    cursor.execute(
        "CREATE TABLE bigrams (prev_word TEXT NOT NULL, next_word TEXT NOT NULL, "
        "frequency INTEGER NOT NULL, PRIMARY KEY (prev_word, next_word))"
    )
    cursor.execute("CREATE INDEX idx_bigram_prev ON bigrams(prev_word)")
    if bigrams:
        print(f"Inserting {len(bigrams):,} bigram records…")
        cursor.executemany(
            "INSERT OR REPLACE INTO bigrams (prev_word, next_word, frequency) VALUES (?, ?, ?)",
            bigrams
        )

    conn.commit()
    conn.close()
    print(f"Database generated successfully at {db_path}")


# ─── CLI ──────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="TaukiTauki Dictionary DB Generator")
    parser.add_argument("--min-freq", type=int, default=1,
                        help="Minimum frequency to include from word_freq.json")
    parser.add_argument("--no-dict", action="store_false", dest="use_dict",
                        help="Skip dictionary and declension generation")
    parser.set_defaults(use_dict=True)
    args = parser.parse_args()
    generate_db(args.min_freq, args.use_dict)
