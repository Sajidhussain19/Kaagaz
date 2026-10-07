"""Dependency-light pipeline contracts for rapid prototyping."""

from dataclasses import dataclass
from typing import Protocol, Sequence


@dataclass(frozen=True)
class Evidence:
    page: int
    bbox: tuple[float, float, float, float]
    text: str


@dataclass(frozen=True)
class ExtractedField:
    name: str
    value: str
    confidence: float
    evidence: Evidence
    verified: bool


class OcrEngine(Protocol):
    def read(self, document: bytes) -> Sequence[Evidence]: ...


class DocumentClassifier(Protocol):
    def classify(self, blocks: Sequence[Evidence]) -> str: ...


class FieldExtractor(Protocol):
    def extract(self, document_type: str, blocks: Sequence[Evidence]) -> Sequence[ExtractedField]: ...


class DocumentPipeline:
    def __init__(self, ocr: OcrEngine, classifier: DocumentClassifier, extractor: FieldExtractor) -> None:
        self._ocr = ocr
        self._classifier = classifier
        self._extractor = extractor

    def run(self, document: bytes) -> tuple[str, Sequence[ExtractedField]]:
        blocks = self._ocr.read(document)
        document_type = self._classifier.classify(blocks)
        return document_type, self._extractor.extract(document_type, blocks)
