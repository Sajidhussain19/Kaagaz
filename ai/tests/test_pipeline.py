import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from kaagaz_ai.pipeline import DocumentPipeline, Evidence, ExtractedField


class StubOcr:
    def read(self, document: bytes):
        return [Evidence(page=1, bbox=(0.1, 0.2, 0.8, 0.3), text="Due 11 Oct 2026")]


class StubClassifier:
    def classify(self, blocks):
        return "bill"


class StubExtractor:
    def extract(self, document_type, blocks):
        return [ExtractedField("due_date", "2026-10-11", 1.0, blocks[0], True)]


class PipelineTest(unittest.TestCase):
    def test_preserves_evidence(self):
        kind, fields = DocumentPipeline(StubOcr(), StubClassifier(), StubExtractor()).run(b"demo")
        self.assertEqual(kind, "bill")
        self.assertEqual(fields[0].evidence.text, "Due 11 Oct 2026")
        self.assertTrue(fields[0].verified)


if __name__ == "__main__":
    unittest.main()
