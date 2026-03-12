import sqlite3
from pathlib import Path


def generate_dictionary_db():
    root = Path(__file__).parent
    db_name = root / "app" / "src" / "main" / "assets" / "dict.db"
    conn = sqlite3.connect(db_name)
    cursor = conn.cursor()

    print(f"Creating {db_name}...")

    # 2. Create the table
    # We use PRIMARY KEY on 'word' to prevent duplicates
    cursor.execute('''
        CREATE TABLE IF NOT EXISTS words (
            word TEXT PRIMARY KEY,
            frequency INTEGER
        )
    ''')

    # 3. Create an index for prefix searching
    # This is CRITICAL for 'LIKE word%' performance in large dictionaries
    cursor.execute('CREATE INDEX IF NOT EXISTS idx_word_prefix ON words(word)')

    # 4. Prepare your data
    # Format: (word, frequency)
    # Higher frequency = appears first in suggestions
    basque_words = [
        ("kaixo", 100),
        ("egun", 95),
        ("gaur", 95),
        ("on", 90),
        ("eta", 85),
        ("ba", 80),
        ("ez", 75),
        ("baina", 70),
        ("duzu", 65),
        ("naiz", 60),
        ("gara", 55),
        ("izan", 50)
    ]

    # 5. Insert the data
    try:
        cursor.executemany('INSERT OR REPLACE INTO words (word, frequency) VALUES (?, ?)', basque_words)
        conn.commit()
        print(f"Successfully inserted {len(basque_words)} words.")
    except sqlite3.Error as e:
        print(f"An error occurred: {e}")
    finally:
        conn.close()

    print("Database generation complete!")

if __name__ == "__main__":
    generate_dictionary_db()
