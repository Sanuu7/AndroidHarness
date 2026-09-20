"""Exercise Room's actual truncate query against SQLite: python scripts/test_message_truncation.py."""
import ast
from pathlib import Path
import re
import sqlite3
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/androidharness/app/data/db/Database.kt').read_text()
QUERY = ''.join(ast.literal_eval(part) for part in re.findall(
    r'"(?:[^"\\]|\\.)*"', SOURCE[SOURCE.index('"DELETE FROM messages WHERE sessionId = :sessionId AND (createdAt'):SOURCE.index('suspend fun deleteMessagesFrom')]))


class MessageTruncationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE messages (id TEXT PRIMARY KEY, sessionId TEXT, createdAt INTEGER)')
        # Restores can insert messages in a different order from their timestamps.
        self.db.executemany('INSERT INTO messages VALUES (?, ?, ?)', [
            ('later', 'chat', 300), ('before', 'chat', 100), ('selected', 'chat', 200),
            ('same-time-after', 'chat', 200), ('other-chat', 'other', 400)])

    def ids(self):
        return [r[0] for r in self.db.execute('SELECT id FROM messages ORDER BY createdAt, rowid')]

    def test_edit_replaces_the_selected_message_and_all_later_history(self):
        self.assertEqual(3, self.db.execute(QUERY, {'sessionId': 'chat', 'messageId': 'selected'}).rowcount)
        self.db.execute('INSERT INTO messages VALUES (?, ?, ?)', ('replacement', 'chat', 500))
        self.assertEqual(['before', 'other-chat', 'replacement'], self.ids())

    def test_equal_timestamps_preserve_the_earlier_message(self):
        self.db.execute(QUERY, {'sessionId': 'chat', 'messageId': 'same-time-after'})
        self.assertEqual(['before', 'selected', 'other-chat'], self.ids())

    def test_stale_target_is_detectable_without_deleting_anything(self):
        before = self.ids()
        self.assertEqual(0, self.db.execute(QUERY, {'sessionId': 'chat', 'messageId': 'missing'}).rowcount)
        self.assertEqual(before, self.ids())

    def test_target_in_another_chat_cannot_delete_history(self):
        before = self.ids()
        self.assertEqual(0, self.db.execute(QUERY, {'sessionId': 'chat', 'messageId': 'other-chat'}).rowcount)
        self.assertEqual(before, self.ids())


if __name__ == '__main__':
    unittest.main()
