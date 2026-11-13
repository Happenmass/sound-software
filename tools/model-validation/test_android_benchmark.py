import unittest
from android_benchmark import parse_output


class AndroidOutputTests(unittest.TestCase):
    def test_extract_actual_cli_metrics_and_optional_rss(self):
        output = '''recognizer created in 1.793 s
Real time factor (RTF): 0.273 / 5.592 = 0.049
{"lang": "<|zh|>", "text": "公开样本"}
Max RSS (KiB): 400000
'''
        parsed = parse_output(output)
        self.assertEqual(parsed["text"], "公开样本")
        self.assertAlmostEqual(parsed["recognizer_rtf"], .273 / 5.592)
        self.assertEqual(parsed["peak_process_rss_bytes"], 409600000)

    def test_startup_failure_is_not_a_successful_result(self):
        with self.assertRaises(ValueError):
            parse_output("CANNOT LINK EXECUTABLE: cannot locate symbol")

    def test_missing_time_is_not_a_zero_time_result(self):
        with self.assertRaises(ValueError):
            parse_output('{"lang":"", "text":"hello"}')


if __name__ == "__main__":
    unittest.main()
