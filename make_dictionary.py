import sqlite3
import json
import re
import argparse
from pathlib import Path

def generate_declensions(word, pos_tags):
    declensions = {word}
    vowels = ('a', 'e', 'i', 'o', 'u')
    ends_with_vowel = word[-1].lower() in vowels

    for pos in pos_tags:
        if any(tag in pos for tag in ['iz.', 'adj.', 'ord.']):
            if ends_with_vowel:
                declensions.update([word + "a", word + "ak", word + "ari", word + "an", word + "aren"])
            else:
                declensions.update([word + "a", word + "ak", word + "ari", word + "ean", word + "aren"])
        elif 'ad.' in pos:
            if word.endswith(('tu', 'du')):
                declensions.update([word[:-2] + "tzen", word + "ko"])
            elif word.endswith('n'):
                declensions.update([word[:-1] + "ten", word + "go"])
            elif word.endswith('i'):
                declensions.update([word[:-1] + "ten", word + "ko"])
    return list(declensions)

def process_hiztegi_json(filepath):
    if not filepath.exists():
        print(f"Warning: Dictionary not found at {filepath}")
        return {}
    
    with open(filepath, 'r', encoding='utf-8') as f:
        data = json.load(f)

    words_dict = {}
    single_word_pattern = re.compile(r'^[\w][\w-]*$', re.UNICODE)

    def extract_pos(adierak_list):
        return {pos for adiera in adierak_list for pos in adiera.get("pos", [])}

    def process_entry(title, adierak):
        if single_word_pattern.match(title):
            pos_tags = extract_pos(adierak)
            for form in generate_declensions(title, pos_tags):
                words_dict[form] = 2

    print("Parsing dictionary and generating declensions...")
    for item in data:
        process_entry(item.get("title", ""), item.get("adierak", []))
        for sub in item.get("subs", []):
            process_entry(sub.get("title", ""), sub.get("adierak", []))
            
    return words_dict

def generate_db(min_freq, use_dict):
    root = Path(__file__).parent
    json_path = root / "data" / "hiztegi_batua.json"
    freq_path = root / "data" / "word_freq.json"
    db_path = root / "app" / "src" / "main" / "assets" / "dict.db"
    
    db_path.parent.mkdir(parents=True, exist_ok=True)
    
    master_words = {}

    # 1. Handle Dictionary Logic
    if use_dict:
        master_words = process_hiztegi_json(json_path)
    else:
        print("Skipping dictionary processing (--no-dict active).")

    # 2. Handle Frequency Logic
    if freq_path.exists():
        print(f"Processing frequencies from {freq_path} (Min threshold: {min_freq})...")
        with open(freq_path, 'r', encoding='utf-8') as f:
            raw_freqs = json.load(f)
            # Filter and merge
            filtered_freqs = {w: f for w, f in raw_freqs.items() if f >= min_freq}
            master_words.update(filtered_freqs)
    else:
        print(f"Error: Frequency file not found at {freq_path}")
        if not use_dict: return

    # 3. SQLite Insertion
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    cursor.execute('DROP TABLE IF EXISTS words')
    cursor.execute('CREATE TABLE words (word TEXT PRIMARY KEY, frequency INTEGER)')
    cursor.execute('CREATE INDEX idx_word_prefix ON words(word)')

    data_to_insert = list(master_words.items())
    print(f"Inserting {len(data_to_insert)} records...")
    
    cursor.executemany('INSERT INTO words (word, frequency) VALUES (?, ?)', data_to_insert)
    
    conn.commit()
    conn.close()
    print(f"Database generated successfully at {db_path}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Basque Dictionary DB Generator")
    parser.add_argument('--min-freq', type=int, default=1, help="Minimum frequency to include from JSON")
    parser.add_argument('--no-dict', action='store_false', dest='use_dict', help="Skip the dictionary and declension generation")
    parser.set_defaults(use_dict=True)

    args = parser.parse_args()
    generate_db(args.min_freq, args.use_dict)
