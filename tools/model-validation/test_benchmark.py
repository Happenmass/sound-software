import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("benchmark", Path(__file__).with_name("benchmark.py"))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class BenchmarkTests(unittest.TestCase):
    def test_codeswitch_preserves_number_and_word_boundaries(self):
        self.assertEqual(benchmark.score_tokens("今天 API 发布 v2，预算 300 元。", "mer"),
                         ["今", "天", "api", "发", "布", "v2", "预", "算", "300", "元"])

    def test_edit_distance_insert_delete_and_replace(self):
        self.assertEqual(benchmark.edit_distance(["a", "b", "c"], ["a", "x", "c", "d"]), 2)
        self.assertEqual(benchmark.edit_distance(["a", "b"], []), 2)

    def test_silence_is_not_divided_by_zero_or_scored_perfect(self):
        r = benchmark.score("", "hello", "wer")
        self.assertIsNone(r["error_rate"])
        self.assertEqual(r["silence_insertions"], 1)

    def test_missing_reference_has_no_invented_quality_score(self):
        self.assertIsNone(benchmark.score(None, "文本", "cer"))

    def test_manifest_rejects_duplicate_sample_ids(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "manifest.json"
            s = {"id": "one", "path": "one.wav", "source": "test", "scope": "fixture"}
            p.write_text(json.dumps({"schema_version": 1, "samples": [s, s]}))
            with self.assertRaisesRegex(ValueError, "unique"):
                benchmark.read_manifest(p)

    def test_atomic_result_write(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "result.json"
            benchmark.write_json(p, {"status": "failed"})
            self.assertEqual(json.loads(p.read_text())["status"], "failed")
            self.assertFalse(p.with_suffix(".json.tmp").exists())


if __name__ == "__main__":
    unittest.main()
