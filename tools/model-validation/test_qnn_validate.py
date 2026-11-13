import unittest
import numpy as np
from qnn_validate import MAX_SAMPLES, classify_success, is_digital_silence, require_window, split_windows


class QnnBoundaryTests(unittest.TestCase):
    def test_exact_sample_limit_and_single_sample_overflow(self):
        require_window(MAX_SAMPLES)
        require_window(MAX_SAMPLES - 1)
        for count in [0, -1, MAX_SAMPLES + 1]:
            with self.assertRaises(ValueError):
                require_window(count)

    def test_lossless_window_coverage(self):
        audio = np.arange(MAX_SAMPLES * 2 + 1)
        windows = split_windows(audio)
        self.assertEqual(windows, [(0, MAX_SAMPLES), (MAX_SAMPLES, MAX_SAMPLES * 2), (MAX_SAMPLES * 2, len(audio))])
        np.testing.assert_array_equal(np.concatenate([audio[a:b] for a, b in windows]), audio)

    def test_digital_silence_does_not_discard_low_amplitude_audio(self):
        self.assertTrue(is_digital_silence(np.zeros(100, dtype=np.int16)))
        self.assertFalse(is_digital_silence(np.array([0, 1, 0], dtype=np.int16)))
        self.assertFalse(is_digital_silence(np.array([], dtype=np.int16)))

    def test_provider_name_alone_is_not_execution_proof(self):
        self.assertFalse(classify_success("loaded libQnnHtp.so backendId: 6"))
        self.assertFalse(classify_success("loaded libQnnCpu.so backendId: 1 QNN_VALIDATION graphExecute succeeded"))
        self.assertTrue(classify_success("loaded libQnnHtp.so backendId: 6 QNN_VALIDATION graphExecute succeeded"))


if __name__ == "__main__":
    unittest.main()
